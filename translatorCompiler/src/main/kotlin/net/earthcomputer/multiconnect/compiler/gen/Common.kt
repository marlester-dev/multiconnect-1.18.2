package net.earthcomputer.multiconnect.compiler.gen

import net.earthcomputer.multiconnect.ap.Registries
import net.earthcomputer.multiconnect.compiler.ArgumentParameter
import net.earthcomputer.multiconnect.compiler.CommonClassNames
import net.earthcomputer.multiconnect.compiler.CompileException
import net.earthcomputer.multiconnect.compiler.DefaultConstruct
import net.earthcomputer.multiconnect.compiler.DefaultConstructedParameter
import net.earthcomputer.multiconnect.compiler.EnumInfo
import net.earthcomputer.multiconnect.compiler.FieldType
import net.earthcomputer.multiconnect.compiler.FilledFromRegistry
import net.earthcomputer.multiconnect.compiler.FilledParameter
import net.earthcomputer.multiconnect.compiler.McFunction
import net.earthcomputer.multiconnect.compiler.McType
import net.earthcomputer.multiconnect.compiler.MessageInfo
import net.earthcomputer.multiconnect.compiler.MessageVariantInfo
import net.earthcomputer.multiconnect.compiler.Polymorphic
import net.earthcomputer.multiconnect.compiler.SuppliedDefaultConstructedParameter
import net.earthcomputer.multiconnect.compiler.classInfoOrNull
import net.earthcomputer.multiconnect.compiler.downcastConstant
import net.earthcomputer.multiconnect.compiler.getMessageVariantInfo
import net.earthcomputer.multiconnect.compiler.groups
import net.earthcomputer.multiconnect.compiler.hasName
import net.earthcomputer.multiconnect.compiler.isIntegral
import net.earthcomputer.multiconnect.compiler.messageVariantInfo
import net.earthcomputer.multiconnect.compiler.node.BinaryExpressionOp
import net.earthcomputer.multiconnect.compiler.node.FunctionCallOp
import net.earthcomputer.multiconnect.compiler.node.IfStmtOp
import net.earthcomputer.multiconnect.compiler.node.ImplicitCastOp
import net.earthcomputer.multiconnect.compiler.node.LambdaOp
import net.earthcomputer.multiconnect.compiler.node.LoadFieldOp
import net.earthcomputer.multiconnect.compiler.node.LoadVariableOp
import net.earthcomputer.multiconnect.compiler.node.McNode
import net.earthcomputer.multiconnect.compiler.node.NewArrayOp
import net.earthcomputer.multiconnect.compiler.node.NewOp
import net.earthcomputer.multiconnect.compiler.node.ReturnStmtOp
import net.earthcomputer.multiconnect.compiler.node.StmtListOp
import net.earthcomputer.multiconnect.compiler.node.StoreFieldStmtOp
import net.earthcomputer.multiconnect.compiler.node.StoreVariableStmtOp
import net.earthcomputer.multiconnect.compiler.node.SwitchOp
import net.earthcomputer.multiconnect.compiler.node.ThrowStmtOp
import net.earthcomputer.multiconnect.compiler.node.VariableId
import net.earthcomputer.multiconnect.compiler.node.createCstOp
import net.earthcomputer.multiconnect.compiler.normalizeIdentifier
import net.earthcomputer.multiconnect.compiler.polymorphicChildren
import net.earthcomputer.multiconnect.compiler.splitPackageClass

internal fun ProtocolCompiler.generatePolymorphicInstantiationGraph(
    message: MessageVariantInfo,
    type: FieldType,
    loadTypeField: McNode,
    paramResolver: (String, McType) -> McNode,
    postConstruct: ((MessageVariantInfo, VariableId) -> McNode)?
): McNode {
    message.polymorphic!!
    val messageType = McType.DeclaredType(message.className)

    fun construct(childMessage: MessageVariantInfo): McNode {
        val childType = McType.DeclaredType(childMessage.className)
        return if (postConstruct == null) {
            McNode(
                ImplicitCastOp(childType, messageType),
                McNode(NewOp(childMessage.className, listOf()))
            )
        } else {
            val varId = VariableId.create()
            McNode(
                StmtListOp,
                McNode(StoreVariableStmtOp(varId, childType, true), McNode(NewOp(childMessage.className, listOf()))),
                postConstruct(childMessage, varId),
                McNode(
                    ReturnStmtOp(messageType),
                    McNode(
                        ImplicitCastOp(childType, messageType),
                        McNode(LoadVariableOp(varId, childType))
                    )
                )
            )
        }
    }

    val children = polymorphicChildren[message.className]?.map { getMessageVariantInfo(it) } ?: emptyList()
    val useSwitchExpression = type.realType != McType.BOOLEAN
            && type.realType != McType.FLOAT
            && type.realType != McType.DOUBLE
            && children.any { it.polymorphic is Polymorphic.Constant<*> }
    val nonSwitchChildren: MutableList<MessageVariantInfo?> = if (useSwitchExpression) {
        children.filterTo(mutableListOf()) { it.polymorphic !is Polymorphic.Constant<*> }
    } else {
        children.toMutableList()
    }
    if (nonSwitchChildren.none { it?.polymorphic is Polymorphic.Otherwise }) {
        nonSwitchChildren += null
    }
    val defaultBranch = if (postConstruct == null && nonSwitchChildren.singleOrNull()?.polymorphic is Polymorphic.Otherwise) {
        construct(nonSwitchChildren.single()!!)
    }
    else {
        McNode(
            StmtListOp,
            nonSwitchChildren.sortedBy {
                when (it?.polymorphic) {
                    is Polymorphic.Constant<*> -> 0
                    is Polymorphic.Condition -> 1
                    is Polymorphic.Otherwise -> 2
                    null -> 2
                }
            }.mapTo(mutableListOf()) { childMessage ->
                when (val polymorphic = childMessage?.polymorphic) {
                    is Polymorphic.Constant<*> -> McNode(
                        IfStmtOp,
                        polymorphic.value.map {
                            val actualValue = if (type.realType == McType.FLOAT) {
                                (it as? Double)?.toFloat() ?: it
                            } else {
                                it
                            }
                            McNode(
                                BinaryExpressionOp("==", type.realType, type.realType),
                                loadTypeField,
                                McNode(createCstOp(actualValue))
                            )
                        }.reduce { left, right ->
                            McNode(BinaryExpressionOp("||", McType.BOOLEAN, McType.BOOLEAN), left, right)
                        },
                        McNode(StmtListOp, McNode(ReturnStmtOp(messageType), construct(childMessage)))
                    )
                    is Polymorphic.Condition -> McNode(
                        IfStmtOp,
                        generateFunctionCallGraph(message.findFunction(polymorphic.value), loadTypeField, paramResolver = paramResolver),
                        McNode(StmtListOp, McNode(ReturnStmtOp(messageType), construct(childMessage)))
                    )
                    is Polymorphic.Otherwise -> McNode(ReturnStmtOp(messageType), construct(childMessage))
                    null -> McNode(
                        ThrowStmtOp,
                        McNode(
                            NewOp("java.lang.IllegalArgumentException", listOf(McType.STRING)),
                            McNode(createCstOp("Could not select polymorphic child of \"${splitPackageClass(message.className).second}\""))
                        )
                    )
                }
            }
        )
    }
    if (!useSwitchExpression) {
        return defaultBranch
    }

    fun <T: Comparable<T>> makeCases(): McNode {
        val (results, cases) =
            children
                .asSequence()
                .filter { it.polymorphic is Polymorphic.Constant<*> }
                .map {
                    @Suppress("UNCHECKED_CAST")
                    it to (it.polymorphic as Polymorphic.Constant<T>)
                }
                .map { (child, group) ->
                    child to when {
                        type.realType.hasName(CommonClassNames.IDENTIFIER) ->
                            group.value.map { (it as String).normalizeIdentifier() }
                        type.realType.isIntegral && type.registry != null ->
                            group.value.filter { it !is String || type.registry.getRawId(it) != null }.map { case ->
                                (case as? String)?.let {
                                    type.registry.getRawId(it)!!.downcastConstant(type.realType)
                                } ?: case
                            }
                        type.realType.classInfoOrNull is EnumInfo ->
                            group.value.map { SwitchOp.EnumCase(it as String) }
                        else -> group.value.map { it.downcastConstant(type.realType) }
                    }
                }
                .filter { it.second.isNotEmpty() }
                .map { (child, lst) ->
                    @Suppress("UNCHECKED_CAST")
                    child to SwitchOp.GroupCase((lst as List<T>).toSortedSet())
                }
                .sortedBy { (_, case) -> case }
                .unzip()
        return McNode(
            SwitchOp(
            cases.toSortedSet(),
            true,
            if (type.realType.hasName(CommonClassNames.IDENTIFIER)) { McType.STRING } else { type.realType },
            messageType
        ),
            (listOf(if (type.realType.hasName(CommonClassNames.IDENTIFIER)) {
                McNode(
                    FunctionCallOp(CommonClassNames.IDENTIFIER, "toString", listOf(type.realType), McType.STRING, false, isStatic = false),
                    loadTypeField
                )
            } else {
                loadTypeField
            }) + results.map(::construct) + listOf(defaultBranch)).toMutableList()
        )
    }
    return makeCases<String>() // use String as placeholder type argument
}



internal fun ProtocolCompiler.generateFunctionCallGraph(function: McFunction, vararg positionalArguments: McNode, paramResolver: (String, McType) -> McNode): McNode {
    val loadParams = function.parameters.mapTo(mutableListOf()) { param ->
        when (param) {
            is ArgumentParameter -> paramResolver(param.name, param.paramType)
            is DefaultConstructedParameter -> generateDefaultConstructGraph(param.paramType)
            is SuppliedDefaultConstructedParameter -> McNode(
                LambdaOp(param.paramType, param.suppliedType, emptyList(), emptyList()),
                generateDefaultConstructGraph(param.suppliedType)
            )
            is FilledParameter -> generateFilledConstructGraph(param.paramType, param.fromRegistry)
        }
    }
    val params = positionalArguments.toMutableList()
    params += loadParams
    return McNode(
        FunctionCallOp(function.owner, function.name, function.positionalParameters + function.parameters.map { it.paramType }, function.returnType, true),
        params
    )
}

internal fun ProtocolCompiler.generateDefaultConstructGraph(classInfo: MessageVariantInfo): McNode {
    val type = McType.DeclaredType(classInfo.className)

    val varId = VariableId.create()
    val vars = mutableMapOf<String, VariableId>()
    val nodes = mutableListOf<McNode>()
    nodes += McNode(StoreVariableStmtOp(varId, type, true), McNode(NewOp(classInfo.className, listOf())))

    var parentInfo: MessageVariantInfo? = null

    if (classInfo.polymorphic != null) {
        if (classInfo.polymorphicParent == null) {
            return if (classInfo.defaultConstruct is DefaultConstruct.SubType) {
                val subclassInfo = classInfo.defaultConstruct.value.messageVariantInfo
                McNode(ImplicitCastOp(classInfo.defaultConstruct.value, type),
                    generateDefaultConstructGraph(subclassInfo)
                )
            } else {
                type.defaultValue()
            }
        } else {
            parentInfo = getMessageVariantInfo(classInfo.polymorphicParent)
            for ((index, field) in parentInfo.fields.withIndex()) {
                val fieldVarId = VariableId.create()
                vars[field.name] = fieldVarId
                val valueNode = if (index == 0 && classInfo.polymorphic is Polymorphic.Constant<*>) {
                    generateConstantGraph(field.type.realType, classInfo.polymorphic.value.first(), field.type.registry)
                } else {
                    generateDefaultConstructGraph(parentInfo, field.type, false) { name, typ -> McNode(LoadVariableOp(vars[name]!!, typ)) }
                }
                nodes += McNode(StoreVariableStmtOp(fieldVarId, field.type.realType, true), valueNode)
                nodes += McNode(
                    StoreFieldStmtOp(type, field.name, field.type.realType),
                    McNode(LoadVariableOp(varId, type)),
                    McNode(LoadVariableOp(fieldVarId, field.type.realType))
                )
            }
        }
    }

    for ((index, field) in classInfo.fields.withIndex()) {
        var isTailRecursive = false
        if (index == classInfo.fields.lastIndex) {
            if (classInfo.tailrec && field.type.realType.hasName(classInfo.className)) {
                isTailRecursive = true
            } else if (parentInfo?.tailrec == true && field.type.realType.hasName(parentInfo.className)) {
                isTailRecursive = true
            }
        }

        val fieldVarId = VariableId.create()
        vars[field.name] = fieldVarId
        val valueNode = generateDefaultConstructGraph(classInfo, field.type, isTailRecursive) { name, typ -> McNode(LoadVariableOp(vars[name]!!, typ)) }
        nodes += McNode(StoreVariableStmtOp(fieldVarId, field.type.realType, true), valueNode)
        nodes += McNode(
            StoreFieldStmtOp(type, field.name, field.type.realType),
            McNode(LoadVariableOp(varId, type)),
            McNode(LoadVariableOp(fieldVarId, field.type.realType))
        )
    }

    nodes += McNode(ReturnStmtOp(type), McNode(LoadVariableOp(varId, type)))

    return McNode(StmtListOp, nodes)
}

internal fun ProtocolCompiler.generateDefaultConstructGraph(type: McType): McNode {
    return when (val classInfo = type.classInfoOrNull) {
        is MessageVariantInfo -> generateDefaultConstructGraph(classInfo)
        is MessageInfo -> {
            val name = (type as McType.DeclaredType).name
            val group = groups[name]!!
            val index = group.binarySearch { (getMessageVariantInfo(it).minVersion ?: -1).compareTo(protocolId) }
            McNode(ImplicitCastOp(McType.DeclaredType(group[index]), McType.DeclaredType(name)),
                generateDefaultConstructGraph(getMessageVariantInfo(group[index]))
            )
        }
        is EnumInfo -> McNode(LoadFieldOp(type, classInfo.values.first(), type, isStatic = true))
        else -> when((type as? McType.DeclaredType)?.name) {
            CommonClassNames.OPTIONAL, CommonClassNames.OPTIONAL_INT, CommonClassNames.OPTIONAL_LONG -> {
                McNode(FunctionCallOp(type.name, "empty", listOf(), type, false))
            }
            CommonClassNames.LIST -> {
                McNode(ImplicitCastOp(McType.DeclaredType(CommonClassNames.ARRAY_LIST), type), McNode(NewOp(
                    CommonClassNames.ARRAY_LIST, listOf())))
            }
            CommonClassNames.INT_LIST -> {
                McNode(ImplicitCastOp(McType.DeclaredType(CommonClassNames.INT_ARRAY_LIST), type), McNode(NewOp(
                    CommonClassNames.INT_ARRAY_LIST, listOf())))
            }
            CommonClassNames.LONG_ARRAY_LIST -> {
                McNode(ImplicitCastOp(McType.DeclaredType(CommonClassNames.LONG_ARRAY_LIST), type), McNode(NewOp(
                    CommonClassNames.LONG_ARRAY_LIST, listOf())))
            }
            CommonClassNames.BITSET -> McNode(NewOp(type.name, listOf()))
            else -> {
                if (type is McType.ArrayType) {
                    McNode(NewArrayOp(type.elementType), McNode(createCstOp(0)))
                } else {
                    type.defaultValue()
                }
            }
        }
    }
}

internal fun ProtocolCompiler.generateDefaultConstructGraph(
    message: MessageVariantInfo,
    type: FieldType,
    isTailRecursive: Boolean,
    paramResolver: (String, McType) -> McNode
): McNode {
    return when (type.defaultConstructInfo) {
        is DefaultConstruct.SubType -> {
            if (isTailRecursive) {
                type.realType.defaultValue()
            } else {
                McNode(ImplicitCastOp(type.defaultConstructInfo.value, type.realType), generateDefaultConstructGraph(type.defaultConstructInfo.value))
            }
        }
        is DefaultConstruct.Constant<*> -> generateConstantGraph(type.realType, type.defaultConstructInfo.value, type.registry)
        is DefaultConstruct.Compute -> generateFunctionCallGraph(message.findFunction(type.defaultConstructInfo.value), paramResolver = paramResolver)
        null -> {
            if (isTailRecursive) {
                type.realType.defaultValue()
            } else {
                generateDefaultConstructGraph(type.realType)
            }
        }
    }
}

internal fun ProtocolCompiler.generateConstantGraph(targetType: McType, value: Any, registry: Registries? = null): McNode {
    return if (targetType.isIntegral && registry != null && value is String) {
        val rawId = registry.getRawId(value) ?: throw CompileException("Unknown value \"$value\" in registry $registry")
        McNode(createCstOp(rawId.downcastConstant(targetType)))
    } else if (targetType.hasName(CommonClassNames.IDENTIFIER)) {
        val (namespace, name) = (value as String).normalizeIdentifier().split(':', limit = 2)
        McNode(NewOp(CommonClassNames.IDENTIFIER, listOf(McType.STRING, McType.STRING)),
            McNode(createCstOp(namespace)),
            McNode(createCstOp(name))
        )
    } else {
        val enumInfo = targetType.classInfoOrNull as? EnumInfo
        if (enumInfo != null) {
            McNode(LoadFieldOp(targetType, value as String, targetType, isStatic = true))
        } else {
            McNode(createCstOp(value.downcastConstant(targetType)))
        }
    }
}

internal fun ProtocolCompiler.generateFilledConstructGraph(type: McType, fromRegistry: FilledFromRegistry?): McNode {
    // TODO: filled construct
    return type.defaultValue()
}
