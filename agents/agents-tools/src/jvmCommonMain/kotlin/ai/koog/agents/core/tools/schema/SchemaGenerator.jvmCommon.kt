package ai.koog.agents.core.tools.schema

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.JavaClassToken
import ai.koog.serialization.JavaTypeToken
import ai.koog.serialization.KSerializerTypeToken
import ai.koog.serialization.KotlinClassToken
import ai.koog.serialization.KotlinTypeToken
import ai.koog.serialization.TypeToken
import ai.koog.serialization.annotations.InternalKoogSerializationApi
import kotlinx.schema.generator.json.FunctionCallingSchemaConfig
import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.generator.json.ReflectionClassJsonSchemaGenerator
import kotlinx.schema.generator.json.ReflectionFunctionCallingSchemaGenerator
import kotlinx.schema.json.JsonSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter

private fun createReflectionClassGenerator(
    jsonSchemaConfig: JsonSchemaConfig,
) = ReflectionClassJsonSchemaGenerator(
    // FIXME don't require Json
    json = Json.Default,
    config = jsonSchemaConfig,
)

private fun createReflectionFunctionGenerator(
    jsonSchemaConfig: JsonSchemaConfig,
) = ReflectionFunctionCallingSchemaGenerator(
    // FIXME don't require Json
    json = Json.Default,
    // FIXME provide convenience constructor FunctionCallingSchemaConfig(jsonSchemaConfig)
    config = FunctionCallingSchemaConfig(
        respectDefaultPresence = jsonSchemaConfig.respectDefaultPresence,
        requireNullableFields = jsonSchemaConfig.requireNullableFields,
        useUnionTypes = jsonSchemaConfig.useUnionTypes,
        useNullableField = jsonSchemaConfig.useNullableField,
        includePolymorphicDiscriminator = jsonSchemaConfig.includePolymorphicDiscriminator,
    ),
)

@OptIn(InternalKoogSerializationApi::class)
public actual fun getJsonSchema(
    typeToken: TypeToken,
    jsonSchemaConfig: JsonSchemaConfig,
): JsonSchema {
    // Try to use KSerializer first
    val kSerializer = findKSerializer(typeToken)

    return if (kSerializer != null) {
        createSerializationGenerator(jsonSchemaConfig)
            .generateSchema(kSerializer.descriptor)
    } else {
        // Fallback to reflection
        val klass = when (typeToken) {
            is KotlinTypeToken -> {
                typeToken.type.classifier as? KClass<*>
                    ?: throw IllegalArgumentException(
                        "Can't generate JSON schema using reflection for ${typeToken.type} since it doesn't represent a Kotlin class"
                    )
            }

            is KotlinClassToken ->
                typeToken.klass

            is JavaTypeToken -> when (val type = typeToken.type) {
                is Class<*> -> type.kotlin

                is ParameterizedType -> {
                    val rawType = type.rawType as? Class<*>
                        ?: throw IllegalArgumentException("Unsupported Type.rawType, must be Class, got: ${type.rawType}")
                    rawType.kotlin
                }

                else -> throw IllegalArgumentException(
                    "Unsupported Java type for schema generation: ${typeToken.type}. Only classes are supported"
                )
            }

            is JavaClassToken ->
                typeToken.klass.kotlin

            is KSerializerTypeToken<*> ->
                throw IllegalStateException("Should not be possible, KSerializer is always present for this type token")
        }

        createReflectionClassGenerator(jsonSchemaConfig)
            .generateSchema(klass)
    }
}

/**
 * Generates a [ToolDescriptor] by generating and converting the function calling schema for the provided [callable]
 *
 * @param callable The callable to generate the schema for.
 * @param toolName Optional custom name. If not provided, [KCallable.name] will be used.
 * @param toolDescription Optional custom description.
 * If not provided, [ai.koog.agents.core.tools.annotations.LLMDescription.description] will be used if the [callable] is annotated with it.
 * @param jsonSchemaConfig Optional custom [JsonSchemaConfig] for the JSON schema generation.
 */
@InternalAgentToolsApi
public fun getToolDescriptor(
    callable: KCallable<*>,
    toolName: String? = null,
    toolDescription: String? = null,
    jsonSchemaConfig: JsonSchemaConfig = defaultJsonSchemaConfig,
): ToolDescriptor {
    val valueParameters = callable.parameters.filter { it.kind == KParameter.Kind.VALUE }
    val parameterSchemas = valueParameters.associateWith { parameter ->
        getJsonSchema(KotlinTypeToken(parameter.type), jsonSchemaConfig)
    }

    if (parameterSchemas.values.any { it.hasRecursiveDefinitions() }) {
        return buildToolDescriptorWithoutFunctionSchema(
            callable = callable,
            parameterSchemas = parameterSchemas,
            toolName = toolName,
            toolDescription = toolDescription,
        )
    }

    val schema = createReflectionFunctionGenerator(jsonSchemaConfig)
        .generateSchema(callable)

    // All parameters for function calling are considered required
    val requiredParameters = schema.parameters.properties
        .orEmpty()
        .map { (name, property) ->
            val parameterInfo = property.toToolParameter(defs = null) // no defs in function calling schema

            ToolParameterDescriptor(
                name = name,
                description = parameterInfo.description,
                type = parameterInfo.type
            )
        }

    return ToolDescriptor(
        name = toolName ?: schema.name,
        description = toolDescription ?: schema.description.orEmpty(),
        requiredParameters = requiredParameters,
    )
}

@OptIn(InternalAgentToolsApi::class)
private fun buildToolDescriptorWithoutFunctionSchema(
    callable: KCallable<*>,
    parameterSchemas: Map<KParameter, JsonSchema>,
    toolName: String?,
    toolDescription: String?,
): ToolDescriptor {
    val referencePolicies = parameterSchemas.mapValues { (_, schema) ->
        analyzeSchemaReferencePolicy(schema)
    }

    val requiredParameters = parameterSchemas.map { (parameter, schema) ->
        val referencePolicy = referencePolicies.getValue(parameter)
        val parameterInfo = schema.toRootToolParameterInfo(referencePolicy)
            .wrapIfNullableParameter(parameter)

        ToolParameterDescriptor(
            name = parameter.name ?: error("Value parameter without name in callable $callable"),
            description = parameter.annotations
                .filterIsInstance<LLMDescription>()
                .firstOrNull()
                ?.value
                ?: parameterInfo.description,
            type = parameterInfo.type,
        )
    }

    val defs = buildMap {
        parameterSchemas.forEach { (parameter, schema) ->
            val referencePolicy = referencePolicies.getValue(parameter)
            schema.defs.orEmpty()
                .filterKeys { it in referencePolicy.defsToReference }
                .forEach { (name, definition) ->
                    put(
                        name,
                        definition.toToolParameter(
                            defs = schema.defs,
                            resolvingRefs = setOf(name),
                            defsToReference = referencePolicy.defsToReference,
                        ).let { info ->
                            ToolParameterDescriptor(
                                name = name,
                                description = info.description,
                                type = info.type,
                            )
                        }
                    )
                }
        }
    }

    return ToolDescriptor(
        name = toolName ?: callable.name,
        description = toolDescription ?: callable.annotations
            .filterIsInstance<LLMDescription>()
            .firstOrNull()
            ?.value
            .orEmpty(),
        requiredParameters = requiredParameters,
        defs = defs,
    )
}

@OptIn(InternalAgentToolsApi::class)
private fun JsonSchema.hasRecursiveDefinitions(): Boolean {
    return analyzeSchemaReferencePolicy(this).defsToReference.isNotEmpty()
}

@OptIn(InternalAgentToolsApi::class)
private fun JsonSchema.toRootToolParameterInfo(
    referencePolicy: SchemaReferencePolicy,
): ToolParameterInfo {
    val rootInfo = toToolParameter(
        defs = defs,
        defsToReference = referencePolicy.defsToReference,
    )

    val rootDefinitionRef = defs.orEmpty()
        .filterKeys { it in referencePolicy.defsToReference }
        .entries
        .firstOrNull { (name, definition) ->
            definition.toToolParameter(
                defs = defs,
                resolvingRefs = setOf(name),
                defsToReference = referencePolicy.defsToReference,
            ).type == rootInfo.type
        }
        ?.key

    return if (rootDefinitionRef != null) {
        ToolParameterInfo(
            type = ai.koog.agents.core.tools.ToolParameterType.Reference("#/\$defs/$rootDefinitionRef"),
            description = rootInfo.description,
        )
    } else {
        rootInfo
    }
}

@OptIn(InternalAgentToolsApi::class)
private fun ToolParameterInfo.wrapIfNullableParameter(parameter: KParameter): ToolParameterInfo {
    if (!parameter.type.isMarkedNullable || type.isNullUnion()) {
        return this
    }

    return ToolParameterInfo(
        type = ai.koog.agents.core.tools.ToolParameterType.AnyOf(
            types = arrayOf(
                ToolParameterDescriptor(
                    name = "",
                    description = "",
                    type = ai.koog.agents.core.tools.ToolParameterType.Null,
                ),
                ToolParameterDescriptor(
                    name = "",
                    description = "",
                    type = type,
                ),
            )
        ),
        description = description,
    )
}

@OptIn(InternalAgentToolsApi::class)
private fun ai.koog.agents.core.tools.ToolParameterType.isNullUnion(): Boolean =
    this is ai.koog.agents.core.tools.ToolParameterType.AnyOf &&
        types.size == 2 &&
        types.any { it.type is ai.koog.agents.core.tools.ToolParameterType.Null }

@InternalKoogSerializationApi
private fun findKSerializer(typeToken: TypeToken): KSerializer<*>? {
    when (typeToken) {
        is KotlinTypeToken ->
            return serializerOrNull(typeToken.type)

        is KotlinClassToken ->
            return try {
                serializer(
                    kClass = typeToken.klass,
                    typeArgumentsSerializers = typeToken.typeArguments
                        .map { findKSerializer(it) ?: return null },
                    isNullable = false,
                )
            } catch (_: SerializationException) {
                null
            }

        is KSerializerTypeToken<*> ->
            return typeToken.serializer

        is JavaTypeToken ->
            return serializerOrNull(typeToken.type)

        is JavaClassToken ->
            return try {
                serializer(
                    kClass = typeToken.klass.kotlin,
                    typeArgumentsSerializers = typeToken.typeArguments
                        .map { findKSerializer(it) ?: return null },
                    isNullable = false,
                )
            } catch (_: SerializationException) {
                null
            }
    }
}
