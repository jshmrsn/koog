package ai.koog.agents.core.tools.schema

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.TypeToken
import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.generator.json.serialization.SerializationClassSchemaIntrospector
import kotlinx.schema.json.AdditionalPropertiesSchema
import kotlinx.schema.json.AllowAdditionalProperties
import kotlinx.schema.json.AnyOfPropertyDefinition
import kotlinx.schema.json.ArrayPropertyDefinition
import kotlinx.schema.json.BooleanPropertyDefinition
import kotlinx.schema.json.CommonSchemaAttributes
import kotlinx.schema.json.DenyAdditionalProperties
import kotlinx.schema.json.JsonSchema
import kotlinx.schema.json.JsonSchemaConstants
import kotlinx.schema.json.NumericPropertyDefinition
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.OneOfPropertyDefinition
import kotlinx.schema.json.PropertyDefinition
import kotlinx.schema.json.ReferencePropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.schema.json.ValuePropertyDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class SchemaReferencePolicy(
    val defsToReference: Set<String>,
)

internal fun createSerializationGenerator(
    jsonSchemaConfig: JsonSchemaConfig,
) = SerializationClassJsonSchemaGenerator(
    introspectorConfig = SerializationClassSchemaIntrospector.Config(
        descriptionExtractor = { annotations ->
            annotations
                .filterIsInstance<LLMDescription>()
                .firstOrNull()?.value
        }
    ),
    json = Json.Default,
    jsonSchemaConfig = jsonSchemaConfig,
)

@InternalAgentToolsApi
public val defaultJsonSchemaConfig: JsonSchemaConfig = JsonSchemaConfig(
    includePolymorphicDiscriminator = false,
)

@InternalAgentToolsApi
public expect fun getJsonSchema(
    typeToken: TypeToken,
    jsonSchemaConfig: JsonSchemaConfig = defaultJsonSchemaConfig,
): JsonSchema

/**
 * Generates a [ToolDescriptor] by generating and converting the JSON schema for the type defined by the provided [argsType]
 *
 * @param argsType Type token representing arguments type.
 * @param toolName Name of the tool.
 * @param toolDescription Optional custom description. If not provided, the description will be obtained from the
 * generated JSON schema for the [argsType]
 * @param jsonSchemaConfig Optional custom [JsonSchemaConfig] for the JSON schema generation.
 */
@InternalAgentToolsApi
public fun getToolDescriptor(
    argsType: TypeToken,
    toolName: String,
    toolDescription: String? = null,
    jsonSchemaConfig: JsonSchemaConfig = defaultJsonSchemaConfig,
): ToolDescriptor {
    val schema = getJsonSchema(argsType, jsonSchemaConfig)
    val referencePolicy = analyzeSchemaReferencePolicy(schema)

    if (JsonSchemaConstants.Types.OBJECT !in schema.type) {
        throw IllegalArgumentException("Only objects are supported as tool schemas, got ${schema.type}")
    }

    val (requiredParameters, optionalParameters) = schema.properties
        .map { (name, property) ->
            val parameterInfo = property.toToolParameter(
                defs = schema.defs,
                defsToReference = referencePolicy.defsToReference,
            )

            ToolParameterDescriptor(
                name = name,
                description = parameterInfo.description,
                type = parameterInfo.type,
            )
        }
        .partition { it.name in schema.required }

    val defs = schema.defs
        .orEmpty()
        .filterKeys { it in referencePolicy.defsToReference }
        .mapValues { (name, definition) ->
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
        }

    return ToolDescriptor(
        name = toolName,
        description = toolDescription ?: schema.description.orEmpty(),
        requiredParameters = requiredParameters,
        optionalParameters = optionalParameters,
        defs = defs,
    )
}

/**
 * Helper class holding information about the [ToolParameterType] along with its optional description.
 */
@InternalAgentToolsApi
public class ToolParameterInfo(
    public val type: ToolParameterType,
    public val description: String,
)

/**
 * Converts a JSON schema property representation [PropertyDefinition] to [ToolParameterInfo], containing our
 * tool parameter representation [ToolParameterType] along with its optional description.
 *
 * @param defs JSON schema definitions map for resolving references.
 */
@InternalAgentToolsApi
public fun PropertyDefinition.toToolParameter(
    defs: Map<String, PropertyDefinition>?
): ToolParameterInfo = toToolParameter(
    defs = defs,
    resolvingRefs = emptySet(),
    defsToReference = emptySet(),
)

@OptIn(InternalAgentToolsApi::class)
internal fun PropertyDefinition.toToolParameter(
    defs: Map<String, PropertyDefinition>?,
    defsToReference: Set<String>,
): ToolParameterInfo = toToolParameter(
    defs = defs,
    resolvingRefs = emptySet(),
    defsToReference = defsToReference,
)

@OptIn(InternalAgentToolsApi::class)
internal fun JsonSchema.toToolParameter(
    defsToReference: Set<String>,
): ToolParameterInfo = toActualPropertyDefinition().toToolParameter(
    defs = defs,
    resolvingRefs = emptySet(),
    defsToReference = defsToReference,
)

@OptIn(InternalAgentToolsApi::class)
internal fun PropertyDefinition.toToolParameter(
    defs: Map<String, PropertyDefinition>?,
    resolvingRefs: Set<String>,
    defsToReference: Set<String>,
): ToolParameterInfo {
    val reusableDefinitionRef = findReusableDefinitionReference(
        defs = defs,
        defsToReference = defsToReference,
        excludedDefinitionNames = resolvingRefs,
    )
    if (reusableDefinitionRef != null) {
        val ref = "${JsonSchemaConstants.Keys.REF_PREFIX}$reusableDefinitionRef"
        val referencedDescription = defs
            ?.get(reusableDefinitionRef)
            ?.propertyDescription
            .orEmpty()

        return ToolParameterInfo(
            type = ToolParameterType.Reference(ref),
            description = propertyDescription ?: referencedDescription,
        )
    }

    return when (this) {
    is ValuePropertyDefinition<*> -> {
        val type = this.type
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Value property definition is missing the 'type' (either null or empty)")

        val isNullableType = JsonSchemaConstants.Types.NULL in type || nullable == true

        val parameterType = when (this) {
            is StringPropertyDefinition -> {
                val enum = this.enum
                val const = (this.constValue as? JsonPrimitive)?.contentOrNull

                when {
                    // Normal enum
                    enum != null -> ToolParameterType.Enum(enum.toTypedArray())

                    // Treat consts as enums with a single value. This is used with polymorphic discriminators
                    const != null -> ToolParameterType.Enum(arrayOf(const))

                    else -> ToolParameterType.String
                }
            }

            is BooleanPropertyDefinition ->
                ToolParameterType.Boolean

            is NumericPropertyDefinition -> when {
                JsonSchemaConstants.Types.INTEGER in type -> ToolParameterType.Integer
                JsonSchemaConstants.Types.NUMBER in type -> ToolParameterType.Float
                else -> throw IllegalArgumentException("Unsupported numeric type: $type")
            }

            is ArrayPropertyDefinition -> {
                ToolParameterType.List(
                    itemsType = items?.toToolParameter(defs, resolvingRefs, defsToReference)?.type
                        ?: throw IllegalArgumentException("Array property definition is missing the 'items' type")
                )
            }

            is ObjectPropertyDefinition -> {
                ToolParameterType.Object(
                    properties = properties
                        .orEmpty()
                        .map { (name, property) ->
                            val parameterInfo = property.toToolParameter(defs, resolvingRefs, defsToReference)

                            ToolParameterDescriptor(
                                name = name,
                                description = parameterInfo.description,
                                type = parameterInfo.type,
                            )
                        },
                    requiredProperties = required.orEmpty(),
                    additionalProperties = when (additionalProperties) {
                        is AllowAdditionalProperties, is AdditionalPropertiesSchema -> true
                        is DenyAdditionalProperties, null -> false
                    },
                    additionalPropertiesType = (additionalProperties as? AdditionalPropertiesSchema)?.schema
                        ?.toToolParameter(defs, resolvingRefs, defsToReference)?.type,
                )
            }

            else ->
                throw IllegalArgumentException("Unsupported value property definition type: $this")
        }

        val effectiveParameterType = if (isNullableType) {
            // emulate type union
            ToolParameterType.AnyOf(
                types = arrayOf(
                    ToolParameterDescriptor(type = ToolParameterType.Null, name = "", description = ""),
                    ToolParameterDescriptor(type = parameterType, name = "", description = ""),
                )
            )
        } else {
            parameterType
        }

        ToolParameterInfo(
            type = effectiveParameterType,
            description = this.description.orEmpty()
        )
    }

    is ReferencePropertyDefinition -> {
        val ref = this.ref
            ?: throw IllegalArgumentException("Reference property definition is missing the 'ref' attribute")
        val refName = ref.removePrefix(JsonSchemaConstants.Keys.REF_PREFIX)

        if (refName in defsToReference || refName in resolvingRefs) {
            val referencedDescription = defs
                ?.get(refName)
                ?.propertyDescription
                .orEmpty()

            ToolParameterInfo(
                type = ToolParameterType.Reference(ref),
                description = this.description ?: referencedDescription,
            )
        } else {
            val defs = defs
                ?: throw IllegalArgumentException(
                    "Encountered a ref in the JSON schema but the schema is missing the defs section"
                )
            val referencedDefinition = defs[refName]
                ?: throw IllegalArgumentException("Can't find ref in defs: $ref. Schema defs: ${defs.keys}")

            referencedDefinition
                .toToolParameter(
                    defs = defs,
                    resolvingRefs = resolvingRefs + refName,
                    defsToReference = defsToReference,
                ).let {
                    ToolParameterInfo(
                        type = it.type,
                        // If ref property itself has a description, use it, otherwise use the referenced type description
                        description = this.description ?: it.description,
                    )
                }
        }
    }

    is AnyOfPropertyDefinition -> {
        val parameterType = ToolParameterType.AnyOf(
            types = anyOf
                .map {
                    val parameterInfo = it.toToolParameter(defs, resolvingRefs, defsToReference)

                    ToolParameterDescriptor(
                        type = parameterInfo.type,
                        description = parameterInfo.description,
                        name = ""
                    )
                }
                .toTypedArray()
        )

        ToolParameterInfo(
            type = parameterType,
            description = this.description.orEmpty(),
        )
    }

    // It isn't fully correct, but to keep the compatibility with ToolDescriptor for now consider oneOf == anyOf
    is OneOfPropertyDefinition -> {
        val parameterType = ToolParameterType.AnyOf(
            types = oneOf
                .map {
                    val parameterInfo = it.toToolParameter(defs, resolvingRefs, defsToReference)

                    ToolParameterDescriptor(
                        type = parameterInfo.type,
                        description = parameterInfo.description,
                        name = ""
                    )
                }
                .toTypedArray()
        )

        ToolParameterInfo(
            type = parameterType,
            description = this.description.orEmpty(),
        )
    }

    /*
     Special case - when JSON schema itself needs to be converted to a tool parameter, e.g. FinishTool in subgraphWithTask,
     with semi-automatic ToolDescriptor construction.
     */
    is JsonSchema ->
        this.toActualPropertyDefinition().toToolParameter(defs, resolvingRefs, defsToReference)

    else ->
        throw IllegalArgumentException("Unsupported property definition type: $this")
}
}

internal fun analyzeSchemaReferencePolicy(schema: JsonSchema): SchemaReferencePolicy =
    analyzeSchemaReferencePolicy(
        roots = listOf(schema.toActualPropertyDefinition()),
        defs = schema.defs.orEmpty(),
    )

internal fun analyzeSchemaReferencePolicy(
    roots: Collection<PropertyDefinition>,
    defs: Map<String, PropertyDefinition>,
): SchemaReferencePolicy {
    val referenceCounts = mutableMapOf<String, Int>()
    val edges = mutableMapOf<String, MutableSet<String>>()

    fun traverse(
        definition: PropertyDefinition,
        currentDefinition: String?,
        activeDefinitions: Set<String>,
        definitionName: String? = null,
    ) {
        val inlineDefinitionMatch = definition.findReusableDefinitionReference(
            defs = defs,
            defsToReference = defs.keys,
            excludedDefinitionNames = setOfNotNull(definitionName),
        )
        if (inlineDefinitionMatch != null) {
            referenceCounts[inlineDefinitionMatch] = (referenceCounts[inlineDefinitionMatch] ?: 0) + 1
            if (currentDefinition != null) {
                edges.getOrPut(currentDefinition) { mutableSetOf() }.add(inlineDefinitionMatch)
            }

            val referencedDefinition = defs[inlineDefinitionMatch] ?: return
            if (inlineDefinitionMatch !in activeDefinitions) {
                traverse(
                    definition = referencedDefinition,
                    currentDefinition = inlineDefinitionMatch,
                    activeDefinitions = activeDefinitions + inlineDefinitionMatch,
                    definitionName = inlineDefinitionMatch,
                )
            }
            return
        }

        when (definition) {
            is ReferencePropertyDefinition -> {
                val ref = definition.ref ?: return
                val refName = ref.removePrefix(JsonSchemaConstants.Keys.REF_PREFIX)
                referenceCounts[refName] = (referenceCounts[refName] ?: 0) + 1
                if (currentDefinition != null) {
                    edges.getOrPut(currentDefinition) { mutableSetOf() }.add(refName)
                }

                val referencedDefinition = defs[refName] ?: return
                if (refName !in activeDefinitions) {
                    traverse(
                        definition = referencedDefinition,
                        currentDefinition = refName,
                        activeDefinitions = activeDefinitions + refName,
                        definitionName = refName,
                    )
                }
            }

            is ArrayPropertyDefinition ->
                definition.items?.let { traverse(it, currentDefinition, activeDefinitions) }

            is ObjectPropertyDefinition -> {
                definition.properties.orEmpty().values.forEach {
                    traverse(it, currentDefinition, activeDefinitions)
                }
                (definition.additionalProperties as? AdditionalPropertiesSchema)?.schema?.let {
                    traverse(it, currentDefinition, activeDefinitions)
                }
            }

            is AnyOfPropertyDefinition ->
                definition.anyOf.forEach { traverse(it, currentDefinition, activeDefinitions) }

            is OneOfPropertyDefinition ->
                definition.oneOf.forEach { traverse(it, currentDefinition, activeDefinitions) }

            is JsonSchema ->
                traverse(definition.toActualPropertyDefinition(), currentDefinition, activeDefinitions)

            else -> Unit
        }
    }

    roots.forEach { traverse(it, currentDefinition = null, activeDefinitions = emptySet()) }

    fun participatesInCycle(start: String): Boolean {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(current: String): Boolean {
            if (!visiting.add(current)) return current == start
            if (!visited.add(current)) {
                visiting.remove(current)
                return false
            }

            val hasCycle = edges[current].orEmpty().any { next ->
                if (next == start) {
                    true
                } else {
                    dfs(next)
                }
            }
            visiting.remove(current)
            return hasCycle
        }

        return dfs(start)
    }

    val cyclicDefinitions = referenceCounts.keys.filterTo(mutableSetOf()) { participatesInCycle(it) }
    val multiplyReferencedDefinitions = referenceCounts
        .filterValues { it > 1 }
        .keys

    return SchemaReferencePolicy(
        defsToReference = cyclicDefinitions + multiplyReferencedDefinitions,
    )
}

private fun PropertyDefinition.findReusableDefinitionReference(
    defs: Map<String, PropertyDefinition>?,
    defsToReference: Set<String>,
    excludedDefinitionNames: Set<String>,
): String? {
    if (this is ReferencePropertyDefinition) {
        return null
    }

    val actualDefinition = asActualPropertyDefinition()
    val actualDefs = defs ?: return null

    val matches = defsToReference
        .asSequence()
        .filter { it !in excludedDefinitionNames }
        .filter { refName ->
            actualDefs[refName]?.let { candidateDefinition ->
                actualDefinition.matchesDefinitionIgnoringDescriptions(candidateDefinition, actualDefs)
            } ?: false
        }
        .toList()

    return matches.singleOrNull()
}

private fun PropertyDefinition.matchesDefinitionIgnoringDescriptions(
    other: PropertyDefinition,
    defs: Map<String, PropertyDefinition>,
    visitedReferencePairs: MutableSet<Pair<String, String>> = mutableSetOf(),
): Boolean {
    val left = asActualPropertyDefinition()
    val right = other.asActualPropertyDefinition()

    if (left is ReferencePropertyDefinition && right is ReferencePropertyDefinition) {
        val leftRefName = left.refNameOrNull() ?: return left.ref == right.ref
        val rightRefName = right.refNameOrNull() ?: return left.ref == right.ref
        if (!visitedReferencePairs.add(leftRefName to rightRefName)) {
            return true
        }

        val leftDefinition = defs[leftRefName] ?: return false
        val rightDefinition = defs[rightRefName] ?: return false
        return leftDefinition.matchesDefinitionIgnoringDescriptions(rightDefinition, defs, visitedReferencePairs)
    }

    if (left is ReferencePropertyDefinition) {
        val leftDefinition = left.refNameOrNull()?.let(defs::get) ?: return false
        return leftDefinition.matchesDefinitionIgnoringDescriptions(right, defs, visitedReferencePairs)
    }

    if (right is ReferencePropertyDefinition) {
        val rightDefinition = right.refNameOrNull()?.let(defs::get) ?: return false
        return left.matchesDefinitionIgnoringDescriptions(rightDefinition, defs, visitedReferencePairs)
    }

    if (left::class != right::class) {
        return false
    }

    return when {
        left is StringPropertyDefinition && right is StringPropertyDefinition ->
            left.type == right.type &&
                left.nullable == right.nullable &&
                left.enum == right.enum &&
                left.constValue == right.constValue

        left is BooleanPropertyDefinition && right is BooleanPropertyDefinition ->
            left.type == right.type &&
                left.nullable == right.nullable

        left is NumericPropertyDefinition && right is NumericPropertyDefinition ->
            left.type == right.type &&
                left.nullable == right.nullable

        left is ArrayPropertyDefinition && right is ArrayPropertyDefinition ->
            left.type == right.type &&
                left.nullable == right.nullable &&
                when {
                    left.items == null && right.items == null -> true
                    left.items != null && right.items != null -> {
                        val leftItems = left.items
                        val rightItems = right.items
                        leftItems != null &&
                            rightItems != null &&
                            leftItems.matchesDefinitionIgnoringDescriptions(rightItems, defs, visitedReferencePairs)
                    }
                    else -> false
                }

        left is ObjectPropertyDefinition && right is ObjectPropertyDefinition ->
            left.type == right.type &&
                left.nullable == right.nullable &&
                left.required.orEmpty() == right.required.orEmpty() &&
                left.properties.orEmpty().keys == right.properties.orEmpty().keys &&
                left.properties.orEmpty().all { (name, property) ->
                    val otherProperty = right.properties.orEmpty()[name] ?: return@all false
                    property.matchesDefinitionIgnoringDescriptions(otherProperty, defs, visitedReferencePairs)
                } &&
                additionalPropertiesMatch(
                    left = left.additionalProperties,
                    right = right.additionalProperties,
                    defs = defs,
                    visitedReferencePairs = visitedReferencePairs,
                )

        left is AnyOfPropertyDefinition && right is AnyOfPropertyDefinition ->
            left.anyOf.size == right.anyOf.size &&
                left.anyOf.zip(right.anyOf).all { (leftProperty, rightProperty) ->
                    leftProperty.matchesDefinitionIgnoringDescriptions(rightProperty, defs, visitedReferencePairs)
                }

        left is OneOfPropertyDefinition && right is OneOfPropertyDefinition ->
            left.oneOf.size == right.oneOf.size &&
                left.oneOf.zip(right.oneOf).all { (leftProperty, rightProperty) ->
                    leftProperty.matchesDefinitionIgnoringDescriptions(rightProperty, defs, visitedReferencePairs)
                }

        else -> false
    }
}

private fun additionalPropertiesMatch(
    left: Any?,
    right: Any?,
    defs: Map<String, PropertyDefinition>,
    visitedReferencePairs: MutableSet<Pair<String, String>>,
): Boolean = when {
    left == null && right == null -> true
    left is AllowAdditionalProperties && right is AllowAdditionalProperties -> true
    left is DenyAdditionalProperties && right is DenyAdditionalProperties -> true
    left is kotlinx.schema.json.AdditionalPropertiesSchema && right is kotlinx.schema.json.AdditionalPropertiesSchema ->
        left.schema.matchesDefinitionIgnoringDescriptions(right.schema, defs, visitedReferencePairs)
    else -> false
}

private fun PropertyDefinition.asActualPropertyDefinition(): PropertyDefinition = when (this) {
    is JsonSchema -> toActualPropertyDefinition()
    else -> this
}

private fun ReferencePropertyDefinition.refNameOrNull(): String? =
    ref?.removePrefix(JsonSchemaConstants.Keys.REF_PREFIX)

private val PropertyDefinition.propertyDescription: String?
    get() = (this as? CommonSchemaAttributes)?.description

/**
 * Transform JsonSchema to suitable property definition, copying only these fields that are actually used by
 * [toToolParameter]
 */
internal fun JsonSchema.toActualPropertyDefinition(): PropertyDefinition = when {
    JsonSchemaConstants.Types.STRING in type ->
        StringPropertyDefinition(
            type = type,
            description = description,
            nullable = nullable,
            constValue = constValue,
            enum = enum?.map { it as JsonPrimitive }?.map { it.content }
        )

    JsonSchemaConstants.Types.BOOLEAN in type ->
        BooleanPropertyDefinition(
            type = type,
            description = description,
            nullable = nullable,
        )

    JsonSchemaConstants.Types.INTEGER in type || JsonSchemaConstants.Types.NUMBER in type ->
        NumericPropertyDefinition(
            type = type,
            description = description,
            nullable = nullable,
        )

    JsonSchemaConstants.Types.ARRAY in type ->
        ArrayPropertyDefinition(
            type = type,
            description = description,
            nullable = nullable,
            items = items,
        )

    JsonSchemaConstants.Types.OBJECT in type ->
        ObjectPropertyDefinition(
            type = type,
            description = description,
            nullable = nullable,
            properties = properties,
            required = required,
            additionalProperties = additionalProperties,
        )

    type.isEmpty() -> when {
        ref != null ->
            ReferencePropertyDefinition(
                ref = ref,
                description = description,
            )

        anyOf != null ->
            AnyOfPropertyDefinition(
                anyOf = anyOf!!,
                description = description,
            )

        oneOf != null ->
            OneOfPropertyDefinition(
                oneOf = oneOf!!,
                description = description,
            )

        else -> throw IllegalArgumentException("Empty type in JSON schema for JsonSchema to PropertyDefinition conversion")
    }

    else -> throw IllegalArgumentException("Unsupported type for JsonSchema to PropertyDefinition conversion: $type")
}
