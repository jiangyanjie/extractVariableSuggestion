package com.google.javascript.jscomp;
import static com.google.javascript.rhino.jstype.JSTypeNative.ARRAY_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.BOOLEAN_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.NULL_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.NUMBER_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.OBJECT_FUNCTION_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.OBJECT_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.REGEXP_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.STRING_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.UNKNOWN_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.VOID_TYPE;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.jscomp.type.ReverseAbstractInterpreter;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.EnumType;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeNative;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.TernaryValue;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

public class TypeCheck implements NodeTraversal.Callback, CompilerPass  {
  final static DiagnosticType UNEXPECTED_TOKEN = DiagnosticType.error("JSC_INTERNAL_ERROR_UNEXPECTED_TOKEN", "Internal Error: Don\'t know how to handle {0}");
  final static DiagnosticType BAD_DELETE = DiagnosticType.warning("JSC_BAD_DELETE_OPERAND", "delete operator needs a reference operand");
  final protected static String OVERRIDING_PROTOTYPE_WITH_NON_OBJECT = "overriding prototype with non-object";
  final static DiagnosticType DETERMINISTIC_TEST = DiagnosticType.warning("JSC_DETERMINISTIC_TEST", "condition always evaluates to {2}\n" + "left : {0}\n" + "right: {1}");
  final static DiagnosticType DETERMINISTIC_TEST_NO_RESULT = DiagnosticType.warning("JSC_DETERMINISTIC_TEST_NO_RESULT", "condition always evaluates to the same value\n" + "left : {0}\n" + "right: {1}");
  final static DiagnosticType INEXISTENT_ENUM_ELEMENT = DiagnosticType.warning("JSC_INEXISTENT_ENUM_ELEMENT", "element {0} does not exist on this enum");
  final static DiagnosticType INEXISTENT_PROPERTY = DiagnosticType.disabled("JSC_INEXISTENT_PROPERTY", "Property {0} never defined on {1}");
  final protected static DiagnosticType NOT_A_CONSTRUCTOR = DiagnosticType.warning("JSC_NOT_A_CONSTRUCTOR", "cannot instantiate non-constructor");
  final static DiagnosticType BIT_OPERATION = DiagnosticType.warning("JSC_BAD_TYPE_FOR_BIT_OPERATION", "operator {0} cannot be applied to {1}");
  final static DiagnosticType NOT_CALLABLE = DiagnosticType.warning("JSC_NOT_FUNCTION_TYPE", "{0} expressions are not callable");
  final static DiagnosticType CONSTRUCTOR_NOT_CALLABLE = DiagnosticType.warning("JSC_CONSTRUCTOR_NOT_CALLABLE", "Constructor {0} should be called with the \"new\" keyword");
  final static DiagnosticType FUNCTION_MASKS_VARIABLE = DiagnosticType.warning("JSC_FUNCTION_MASKS_VARIABLE", "function {0} masks variable (IE bug)");
  final static DiagnosticType MULTIPLE_VAR_DEF = DiagnosticType.warning("JSC_MULTIPLE_VAR_DEF", "declaration of multiple variables with shared type information");
  final static DiagnosticType ENUM_DUP = DiagnosticType.error("JSC_ENUM_DUP", "enum element {0} already defined");
  final static DiagnosticType ENUM_NOT_CONSTANT = DiagnosticType.warning("JSC_ENUM_NOT_CONSTANT", "enum key {0} must be a syntactic constant");
  final static DiagnosticType INVALID_INTERFACE_MEMBER_DECLARATION = DiagnosticType.warning("JSC_INVALID_INTERFACE_MEMBER_DECLARATION", "interface members can only be empty property declarations," + " empty functions{0}");
  final static DiagnosticType INTERFACE_FUNCTION_NOT_EMPTY = DiagnosticType.warning("JSC_INTERFACE_FUNCTION_NOT_EMPTY", "interface member functions must have an empty body");
  final static DiagnosticType CONFLICTING_EXTENDED_TYPE = DiagnosticType.warning("JSC_CONFLICTING_EXTENDED_TYPE", "{1} cannot extend this type; {0}s can only extend {0}s");
  final static DiagnosticType CONFLICTING_IMPLEMENTED_TYPE = DiagnosticType.warning("JSC_CONFLICTING_IMPLEMENTED_TYPE", "{0} cannot implement this type; " + "an interface can only extend, but not implement interfaces");
  final static DiagnosticType BAD_IMPLEMENTED_TYPE = DiagnosticType.warning("JSC_IMPLEMENTS_NON_INTERFACE", "can only implement interfaces");
  final static DiagnosticType HIDDEN_SUPERCLASS_PROPERTY = DiagnosticType.warning("JSC_HIDDEN_SUPERCLASS_PROPERTY", "property {0} already defined on superclass {1}; " + "use @override to override it");
  final static DiagnosticType HIDDEN_INTERFACE_PROPERTY = DiagnosticType.warning("JSC_HIDDEN_INTERFACE_PROPERTY", "property {0} already defined on interface {1}; " + "use @override to override it");
  final static DiagnosticType HIDDEN_SUPERCLASS_PROPERTY_MISMATCH = DiagnosticType.warning("JSC_HIDDEN_SUPERCLASS_PROPERTY_MISMATCH", "mismatch of the {0} property type and the type " + "of the property it overrides from superclass {1}\n" + "original: {2}\n" + "override: {3}");
  final static DiagnosticType UNKNOWN_OVERRIDE = DiagnosticType.warning("JSC_UNKNOWN_OVERRIDE", "property {0} not defined on any superclass of {1}");
  final static DiagnosticType INTERFACE_METHOD_OVERRIDE = DiagnosticType.warning("JSC_INTERFACE_METHOD_OVERRIDE", "property {0} is already defined by the {1} extended interface");
  final static DiagnosticType UNKNOWN_EXPR_TYPE = DiagnosticType.warning("JSC_UNKNOWN_EXPR_TYPE", "could not determine the type of this expression");
  final static DiagnosticType UNRESOLVED_TYPE = DiagnosticType.warning("JSC_UNRESOLVED_TYPE", "could not resolve the name {0} to a type");
  final static DiagnosticType WRONG_ARGUMENT_COUNT = DiagnosticType.warning("JSC_WRONG_ARGUMENT_COUNT", "Function {0}: called with {1} argument(s). " + "Function requires at least {2} argument(s){3}.");
  final static DiagnosticType ILLEGAL_IMPLICIT_CAST = DiagnosticType.warning("JSC_ILLEGAL_IMPLICIT_CAST", "Illegal annotation on {0}. @implicitCast may only be used in " + "externs.");
  final static DiagnosticType INCOMPATIBLE_EXTENDED_PROPERTY_TYPE = DiagnosticType.warning("JSC_INCOMPATIBLE_EXTENDED_PROPERTY_TYPE", "Interface {0} has a property {1} with incompatible types in " + "its super interfaces {2} and {3}");
  final static DiagnosticType EXPECTED_THIS_TYPE = DiagnosticType.warning("JSC_EXPECTED_THIS_TYPE", "\"{0}\" must be called with a \"this\" type");
  final static DiagnosticType IN_USED_WITH_STRUCT = DiagnosticType.warning("JSC_IN_USED_WITH_STRUCT", "Cannot use the IN operator with structs");
  final static DiagnosticType ILLEGAL_PROPERTY_CREATION = DiagnosticType.warning("JSC_ILLEGAL_PROPERTY_CREATION", "Cannot add a property to a struct instance " + "after it is constructed.");
  final static DiagnosticType ILLEGAL_OBJLIT_KEY = DiagnosticType.warning("ILLEGAL_OBJLIT_KEY", "Illegal key, the object literal is a {0}");
  final static DiagnosticGroup ALL_DIAGNOSTICS = new DiagnosticGroup(DETERMINISTIC_TEST, DETERMINISTIC_TEST_NO_RESULT, INEXISTENT_ENUM_ELEMENT, INEXISTENT_PROPERTY, NOT_A_CONSTRUCTOR, BIT_OPERATION, NOT_CALLABLE, CONSTRUCTOR_NOT_CALLABLE, FUNCTION_MASKS_VARIABLE, MULTIPLE_VAR_DEF, ENUM_DUP, ENUM_NOT_CONSTANT, INVALID_INTERFACE_MEMBER_DECLARATION, INTERFACE_FUNCTION_NOT_EMPTY, CONFLICTING_EXTENDED_TYPE, CONFLICTING_IMPLEMENTED_TYPE, BAD_IMPLEMENTED_TYPE, HIDDEN_SUPERCLASS_PROPERTY, HIDDEN_INTERFACE_PROPERTY, HIDDEN_SUPERCLASS_PROPERTY_MISMATCH, UNKNOWN_OVERRIDE, INTERFACE_METHOD_OVERRIDE, UNKNOWN_EXPR_TYPE, UNRESOLVED_TYPE, WRONG_ARGUMENT_COUNT, ILLEGAL_IMPLICIT_CAST, INCOMPATIBLE_EXTENDED_PROPERTY_TYPE, EXPECTED_THIS_TYPE, IN_USED_WITH_STRUCT, ILLEGAL_PROPERTY_CREATION, ILLEGAL_OBJLIT_KEY, RhinoErrorReporter.TYPE_PARSE_ERROR, TypedScopeCreator.UNKNOWN_LENDS, TypedScopeCreator.LENDS_ON_NON_OBJECT, TypedScopeCreator.CTOR_INITIALIZER, TypedScopeCreator.IFACE_INITIALIZER, FunctionTypeBuilder.THIS_TYPE_NON_OBJECT);
  final private AbstractCompiler compiler;
  final private TypeValidator validator;
  final private ReverseAbstractInterpreter reverseInterpreter;
  final private JSTypeRegistry typeRegistry;
  private Scope topScope;
  private MemoizedScopeCreator scopeCreator;
  final private CheckLevel reportMissingOverride;
  final private CheckLevel reportUnknownTypes;
  private boolean reportMissingProperties = true;
  private InferJSDocInfo inferJSDocInfo = null;
  private int typedCount = 0;
  private int nullCount = 0;
  private int unknownCount = 0;
  private boolean inExterns;
  private int noTypeCheckSection = 0;
  TypeCheck(AbstractCompiler compiler, ReverseAbstractInterpreter reverseInterpreter, JSTypeRegistry typeRegistry) {
    this(compiler, reverseInterpreter, typeRegistry, null, null, CheckLevel.WARNING, CheckLevel.OFF);
  }
  public TypeCheck(AbstractCompiler compiler, ReverseAbstractInterpreter reverseInterpreter, JSTypeRegistry typeRegistry, CheckLevel reportMissingOverride, CheckLevel reportUnknownTypes) {
    this(compiler, reverseInterpreter, typeRegistry, null, null, reportMissingOverride, reportUnknownTypes);
  }
  public TypeCheck(AbstractCompiler compiler, ReverseAbstractInterpreter reverseInterpreter, JSTypeRegistry typeRegistry, Scope topScope, MemoizedScopeCreator scopeCreator, CheckLevel reportMissingOverride, CheckLevel reportUnknownTypes) {
    super();
    this.compiler = compiler;
    this.validator = compiler.getTypeValidator();
    this.reverseInterpreter = reverseInterpreter;
    this.typeRegistry = typeRegistry;
    this.topScope = topScope;
    this.scopeCreator = scopeCreator;
    this.reportMissingOverride = reportMissingOverride;
    this.reportUnknownTypes = reportUnknownTypes;
    this.inferJSDocInfo = new InferJSDocInfo(compiler);
  }
  private JSType getJSType(Node n) {
    JSType jsType = n.getJSType();
    if(jsType == null) {
      return getNativeType(UNKNOWN_TYPE);
    }
    else {
      return jsType;
    }
  }
  private JSType getNativeType(JSTypeNative typeId) {
    return typeRegistry.getNativeType(typeId);
  }
  public Scope processForTesting(Node externsRoot, Node jsRoot) {
    Preconditions.checkState(scopeCreator == null);
    Preconditions.checkState(topScope == null);
    Preconditions.checkState(jsRoot.getParent() != null);
    Node externsAndJsRoot = jsRoot.getParent();
    scopeCreator = new MemoizedScopeCreator(new TypedScopeCreator(compiler));
    topScope = scopeCreator.createScope(externsAndJsRoot, null);
    TypeInferencePass inference = new TypeInferencePass(compiler, reverseInterpreter, topScope, scopeCreator);
    inference.process(externsRoot, jsRoot);
    process(externsRoot, jsRoot);
    return topScope;
  }
  TypeCheck reportMissingProperties(boolean report) {
    reportMissingProperties = report;
    return this;
  }
  private static boolean hasUnknownOrEmptySupertype(FunctionType ctor) {
    Preconditions.checkArgument(ctor.isConstructor() || ctor.isInterface());
    Preconditions.checkArgument(!ctor.isUnknownType());
    while(true){
      ObjectType maybeSuperInstanceType = ctor.getPrototype().getImplicitPrototype();
      if(maybeSuperInstanceType == null) {
        return false;
      }
      if(maybeSuperInstanceType.isUnknownType() || maybeSuperInstanceType.isEmptyType()) {
        return true;
      }
      ctor = maybeSuperInstanceType.getConstructor();
      if(ctor == null) {
        return false;
      }
      Preconditions.checkState(ctor.isConstructor() || ctor.isInterface());
    }
  }
  private boolean isPropertyTest(Node getProp) {
    Node parent = getProp.getParent();
    switch (parent.getType()){
      case Token.CALL:
      return parent.getFirstChild() != getProp && compiler.getCodingConvention().isPropertyTestFunction(parent);
      case Token.IF:
      case Token.WHILE:
      case Token.DO:
      case Token.FOR:
      return NodeUtil.getConditionExpression(parent) == getProp;
      case Token.INSTANCEOF:
      case Token.TYPEOF:
      return true;
      case Token.AND:
      case Token.HOOK:
      return parent.getFirstChild() == getProp;
      case Token.NOT:
      return parent.getParent().isOr() && parent.getParent().getFirstChild() == parent;
    }
    return false;
  }
  private boolean propertyIsImplicitCast(ObjectType type, String prop) {
    for(; type != null; type = type.getImplicitPrototype()) {
      JSDocInfo docInfo = type.getOwnPropertyJSDocInfo(prop);
      if(docInfo != null && docInfo.isImplicitCast()) {
        return true;
      }
    }
    return false;
  }
  @Override() public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    checkNoTypeCheckSection(n, true);
    switch (n.getType()){
      case Token.FUNCTION:
      final Scope outerScope = t.getScope();
      final String functionPrivateName = n.getFirstChild().getString();
      if(functionPrivateName != null && functionPrivateName.length() > 0 && outerScope.isDeclared(functionPrivateName, false) && !(outerScope.getVar(functionPrivateName).getType() instanceof FunctionType)) {
        report(t, n, FUNCTION_MASKS_VARIABLE, functionPrivateName);
      }
      break ;
    }
    return true;
  }
  boolean visitName(NodeTraversal t, Node n, Node parent) {
    int parentNodeType = parent.getType();
    if(parentNodeType == Token.FUNCTION || parentNodeType == Token.CATCH || parentNodeType == Token.PARAM_LIST || parentNodeType == Token.VAR) {
      return false;
    }
    JSType type = n.getJSType();
    if(type == null) {
      type = getNativeType(UNKNOWN_TYPE);
      Var var = t.getScope().getVar(n.getString());
      if(var != null) {
        JSType varType = var.getType();
        if(varType != null) {
          type = varType;
        }
      }
    }
    ensureTyped(t, n, type);
    return true;
  }
  double getTypedPercent() {
    int total = nullCount + unknownCount + typedCount;
    return (total == 0) ? 0.0D : (100.0D * typedCount) / total;
  }
  public void check(Node node, boolean externs) {
    Preconditions.checkNotNull(node);
    NodeTraversal t = new NodeTraversal(compiler, this, scopeCreator);
    inExterns = externs;
    t.traverseWithScope(node, topScope);
    if(externs) {
      inferJSDocInfo.process(node, null);
    }
    else {
      inferJSDocInfo.process(null, node);
    }
  }
  private void checkDeclaredPropertyInheritance(NodeTraversal t, Node n, FunctionType ctorType, String propertyName, JSDocInfo info, JSType propertyType) {
    if(hasUnknownOrEmptySupertype(ctorType)) {
      return ;
    }
    FunctionType superClass = ctorType.getSuperClassConstructor();
    boolean superClassHasProperty = superClass != null && superClass.getInstanceType().hasProperty(propertyName);
    boolean superClassHasDeclaredProperty = superClass != null && superClass.getInstanceType().isPropertyTypeDeclared(propertyName);
    boolean superInterfaceHasProperty = false;
    boolean superInterfaceHasDeclaredProperty = false;
    if(ctorType.isInterface()) {
      for (ObjectType interfaceType : ctorType.getExtendedInterfaces()) {
        superInterfaceHasProperty = superInterfaceHasProperty || interfaceType.hasProperty(propertyName);
        superInterfaceHasDeclaredProperty = superInterfaceHasDeclaredProperty || interfaceType.isPropertyTypeDeclared(propertyName);
      }
    }
    boolean declaredOverride = info != null && info.isOverride();
    boolean foundInterfaceProperty = false;
    if(ctorType.isConstructor()) {
      for (JSType implementedInterface : ctorType.getAllImplementedInterfaces()) {
        if(implementedInterface.isUnknownType() || implementedInterface.isEmptyType()) {
          continue ;
        }
        FunctionType interfaceType = implementedInterface.toObjectType().getConstructor();
        Preconditions.checkNotNull(interfaceType);
        boolean interfaceHasProperty = interfaceType.getPrototype().hasProperty(propertyName);
        foundInterfaceProperty = foundInterfaceProperty || interfaceHasProperty;
        if(reportMissingOverride.isOn() && !declaredOverride && interfaceHasProperty) {
          compiler.report(t.makeError(n, reportMissingOverride, HIDDEN_INTERFACE_PROPERTY, propertyName, interfaceType.getTopMostDefiningType(propertyName).toString()));
        }
      }
    }
    if(!declaredOverride && !superClassHasProperty && !superInterfaceHasProperty) {
      return ;
    }
    ObjectType topInstanceType = superClassHasDeclaredProperty ? superClass.getTopMostDefiningType(propertyName) : null;
    boolean declaredLocally = ctorType.isConstructor() && (ctorType.getPrototype().hasOwnProperty(propertyName) || ctorType.getInstanceType().hasOwnProperty(propertyName));
    if(reportMissingOverride.isOn() && !declaredOverride && superClassHasDeclaredProperty && declaredLocally) {
      compiler.report(t.makeError(n, reportMissingOverride, HIDDEN_SUPERCLASS_PROPERTY, propertyName, topInstanceType.toString()));
    }
    if(superClassHasDeclaredProperty) {
      JSType superClassPropType = superClass.getInstanceType().getPropertyType(propertyName);
      if(!propertyType.isSubtype(superClassPropType)) {
        compiler.report(t.makeError(n, HIDDEN_SUPERCLASS_PROPERTY_MISMATCH, propertyName, topInstanceType.toString(), superClassPropType.toString(), propertyType.toString()));
      }
    }
    else 
      if(superInterfaceHasDeclaredProperty) {
        for (ObjectType interfaceType : ctorType.getExtendedInterfaces()) {
          if(interfaceType.hasProperty(propertyName)) {
            JSType superPropertyType = interfaceType.getPropertyType(propertyName);
            if(!propertyType.isSubtype(superPropertyType)) {
              topInstanceType = interfaceType.getConstructor().getTopMostDefiningType(propertyName);
              compiler.report(t.makeError(n, HIDDEN_SUPERCLASS_PROPERTY_MISMATCH, propertyName, topInstanceType.toString(), superPropertyType.toString(), propertyType.toString()));
            }
          }
        }
      }
      else 
        if(!foundInterfaceProperty && !superClassHasProperty && !superInterfaceHasProperty) {
          compiler.report(t.makeError(n, UNKNOWN_OVERRIDE, propertyName, ctorType.getInstanceType().toString()));
        }
  }
  private void checkEnumAlias(NodeTraversal t, JSDocInfo declInfo, Node value) {
    if(declInfo == null || !declInfo.hasEnumParameterType()) {
      return ;
    }
    JSType valueType = getJSType(value);
    if(!valueType.isEnumType()) {
      return ;
    }
    EnumType valueEnumType = valueType.toMaybeEnumType();
    JSType valueEnumPrimitiveType = valueEnumType.getElementsType().getPrimitiveType();
    validator.expectCanAssignTo(t, value, valueEnumPrimitiveType, declInfo.getEnumParameterType().evaluate(t.getScope(), typeRegistry), "incompatible enum element types");
  }
  private void checkInterfaceConflictProperties(NodeTraversal t, Node n, String functionName, HashMap<String, ObjectType> properties, HashMap<String, ObjectType> currentProperties, ObjectType interfaceType) {
    ObjectType implicitProto = interfaceType.getImplicitPrototype();
    Set<String> currentPropertyNames;
    if(implicitProto == null) {
      currentPropertyNames = ImmutableSet.of();
    }
    else {
      currentPropertyNames = implicitProto.getOwnPropertyNames();
    }
    for (String name : currentPropertyNames) {
      ObjectType oType = properties.get(name);
      if(oType != null) {
        if(!interfaceType.getPropertyType(name).isEquivalentTo(oType.getPropertyType(name))) {
          compiler.report(t.makeError(n, INCOMPATIBLE_EXTENDED_PROPERTY_TYPE, functionName, name, oType.toString(), interfaceType.toString()));
        }
      }
      currentProperties.put(name, interfaceType);
    }
    for (ObjectType iType : interfaceType.getCtorExtendedInterfaces()) {
      checkInterfaceConflictProperties(t, n, functionName, properties, currentProperties, iType);
    }
  }
  private void checkNoTypeCheckSection(Node n, boolean enterSection) {
    switch (n.getType()){
      case Token.SCRIPT:
      case Token.BLOCK:
      case Token.VAR:
      case Token.FUNCTION:
      case Token.ASSIGN:
      JSDocInfo info = n.getJSDocInfo();
      if(info != null && info.isNoTypeCheck()) {
        if(enterSection) {
          noTypeCheckSection++;
        }
        else {
          noTypeCheckSection--;
        }
      }
      validator.setShouldReport(noTypeCheckSection == 0);
      break ;
    }
  }
  private void checkPropCreation(NodeTraversal t, Node lvalue) {
    if(lvalue.isGetProp()) {
      Node obj = lvalue.getFirstChild();
      Node prop = lvalue.getLastChild();
      JSType objType = getJSType(obj);
      String pname = prop.getString();
      if(objType.isStruct() && !objType.hasProperty(pname)) {
        if(!(obj.isThis() && getJSType(t.getScope().getRootNode()).isConstructor())) {
          report(t, prop, ILLEGAL_PROPERTY_CREATION);
        }
      }
    }
  }
  private void checkPropertyAccess(JSType childType, String propName, NodeTraversal t, Node n) {
    JSType propType = getJSType(n);
    if(propType.isEquivalentTo(typeRegistry.getNativeType(UNKNOWN_TYPE))) {
      childType = childType.autobox();
      ObjectType objectType = ObjectType.cast(childType);
      if(objectType != null) {
        if(!objectType.hasProperty(propName) || objectType.isEquivalentTo(typeRegistry.getNativeType(UNKNOWN_TYPE))) {
          if(objectType instanceof EnumType) {
            report(t, n, INEXISTENT_ENUM_ELEMENT, propName);
          }
          else {
            checkPropertyAccessHelper(objectType, propName, t, n);
          }
        }
      }
      else {
        checkPropertyAccessHelper(childType, propName, t, n);
      }
    }
  }
  private void checkPropertyAccessHelper(JSType objectType, String propName, NodeTraversal t, Node n) {
    if(!objectType.isEmptyType() && reportMissingProperties && !isPropertyTest(n)) {
      if(!typeRegistry.canPropertyBeDefined(objectType, propName)) {
        report(t, n, INEXISTENT_PROPERTY, propName, validator.getReadableJSTypeName(n.getFirstChild(), true));
      }
    }
  }
  private void checkPropertyInheritanceOnGetpropAssign(NodeTraversal t, Node assign, Node object, String property, JSDocInfo info, JSType propertyType) {
    if(object.isGetProp()) {
      Node object2 = object.getFirstChild();
      String property2 = NodeUtil.getStringValue(object.getLastChild());
      if("prototype".equals(property2)) {
        JSType jsType = getJSType(object2);
        if(jsType.isFunctionType()) {
          FunctionType functionType = jsType.toMaybeFunctionType();
          if(functionType.isConstructor() || functionType.isInterface()) {
            checkDeclaredPropertyInheritance(t, assign, functionType, property, info, propertyType);
          }
        }
      }
    }
  }
  private void checkTypeofString(NodeTraversal t, Node n, String s) {
    if(!(s.equals("number") || s.equals("string") || s.equals("boolean") || s.equals("undefined") || s.equals("function") || s.equals("object") || s.equals("unknown"))) {
      validator.expectValidTypeofName(t, n, s);
    }
  }
  private void doPercentTypedAccounting(NodeTraversal t, Node n) {
    JSType type = n.getJSType();
    if(type == null) {
      nullCount++;
    }
    else 
      if(type.isUnknownType()) {
        if(reportUnknownTypes.isOn()) {
          compiler.report(t.makeError(n, reportUnknownTypes, UNKNOWN_EXPR_TYPE));
        }
        unknownCount++;
      }
      else {
        typedCount++;
      }
  }
  private void ensureTyped(NodeTraversal t, Node n) {
    ensureTyped(t, n, getNativeType(UNKNOWN_TYPE));
  }
  private void ensureTyped(NodeTraversal t, Node n, JSType type) {
    Preconditions.checkState(!n.isFunction() || type.isFunctionType() || type.isUnknownType());
    JSDocInfo info = n.getJSDocInfo();
    if(info != null) {
      if(info.hasType()) {
        JSType infoType = info.getType().evaluate(t.getScope(), typeRegistry);
        validator.expectCanCast(t, n, infoType, type);
        type = infoType;
      }
      if(info.isImplicitCast() && !inExterns) {
        String propName = n.isGetProp() ? n.getLastChild().getString() : "(missing)";
        compiler.report(t.makeError(n, ILLEGAL_IMPLICIT_CAST, propName));
      }
    }
    if(n.getJSType() == null) {
      n.setJSType(type);
    }
  }
  private void ensureTyped(NodeTraversal t, Node n, JSTypeNative type) {
    ensureTyped(t, n, getNativeType(type));
  }
  @Override() public void process(Node externsRoot, Node jsRoot) {
    Preconditions.checkNotNull(scopeCreator);
    Preconditions.checkNotNull(topScope);
    Node externsAndJs = jsRoot.getParent();
    Preconditions.checkState(externsAndJs != null);
    Preconditions.checkState(externsRoot == null || externsAndJs.hasChild(externsRoot));
    if(externsRoot != null) {
      check(externsRoot, true);
    }
    check(jsRoot, false);
  }
  private void report(NodeTraversal t, Node n, DiagnosticType diagnosticType, String ... arguments) {
    if(noTypeCheckSection == 0) {
      t.report(n, diagnosticType, arguments);
    }
  }
  @Override() public void visit(NodeTraversal t, Node n, Node parent) {
    JSType childType;
    JSType leftType;
    JSType rightType;
    Node left;
    Node right;
    boolean typeable = true;
    switch (n.getType()){
      case Token.CAST:
      Node expr = n.getFirstChild();
      ensureTyped(t, n, getJSType(expr));
      JSType castType = getJSType(n);
      JSType exprType = getJSType(expr);
      if(castType.isSubtype(exprType)) {
        expr.setJSType(castType);
      }
      break ;
      case Token.NAME:
      typeable = visitName(t, n, parent);
      break ;
      case Token.PARAM_LIST:
      typeable = false;
      break ;
      case Token.COMMA:
      JSType var_984 = getJSType(n.getLastChild());
      ensureTyped(t, n, var_984);
      break ;
      case Token.TRUE:
      case Token.FALSE:
      ensureTyped(t, n, BOOLEAN_TYPE);
      break ;
      case Token.THIS:
      ensureTyped(t, n, t.getScope().getTypeOfThis());
      break ;
      case Token.NULL:
      ensureTyped(t, n, NULL_TYPE);
      break ;
      case Token.NUMBER:
      ensureTyped(t, n, NUMBER_TYPE);
      break ;
      case Token.STRING:
      ensureTyped(t, n, STRING_TYPE);
      break ;
      case Token.STRING_KEY:
      typeable = false;
      break ;
      case Token.GETTER_DEF:
      case Token.SETTER_DEF:
      break ;
      case Token.ARRAYLIT:
      ensureTyped(t, n, ARRAY_TYPE);
      break ;
      case Token.REGEXP:
      ensureTyped(t, n, REGEXP_TYPE);
      break ;
      case Token.GETPROP:
      visitGetProp(t, n, parent);
      typeable = !(parent.isAssign() && parent.getFirstChild() == n);
      break ;
      case Token.GETELEM:
      visitGetElem(t, n);
      typeable = false;
      break ;
      case Token.VAR:
      visitVar(t, n);
      typeable = false;
      break ;
      case Token.NEW:
      visitNew(t, n);
      break ;
      case Token.CALL:
      visitCall(t, n);
      typeable = !parent.isExprResult();
      break ;
      case Token.RETURN:
      visitReturn(t, n);
      typeable = false;
      break ;
      case Token.DEC:
      case Token.INC:
      left = n.getFirstChild();
      checkPropCreation(t, left);
      validator.expectNumber(t, left, getJSType(left), "increment/decrement");
      ensureTyped(t, n, NUMBER_TYPE);
      break ;
      case Token.NOT:
      ensureTyped(t, n, BOOLEAN_TYPE);
      break ;
      case Token.VOID:
      ensureTyped(t, n, VOID_TYPE);
      break ;
      case Token.TYPEOF:
      ensureTyped(t, n, STRING_TYPE);
      break ;
      case Token.BITNOT:
      childType = getJSType(n.getFirstChild());
      if(!childType.matchesInt32Context()) {
        report(t, n, BIT_OPERATION, NodeUtil.opToStr(n.getType()), childType.toString());
      }
      ensureTyped(t, n, NUMBER_TYPE);
      break ;
      case Token.POS:
      case Token.NEG:
      left = n.getFirstChild();
      validator.expectNumber(t, left, getJSType(left), "sign operator");
      ensureTyped(t, n, NUMBER_TYPE);
      break ;
      case Token.EQ:
      case Token.NE:
      case Token.SHEQ:
      case Token.SHNE:
      {
        left = n.getFirstChild();
        right = n.getLastChild();
        if(left.isTypeOf()) {
          if(right.isString()) {
            checkTypeofString(t, right, right.getString());
          }
        }
        else 
          if(right.isTypeOf() && left.isString()) {
            checkTypeofString(t, left, left.getString());
          }
        leftType = getJSType(left);
        rightType = getJSType(right);
        JSType leftTypeRestricted = leftType.restrictByNotNullOrUndefined();
        JSType rightTypeRestricted = rightType.restrictByNotNullOrUndefined();
        TernaryValue result = TernaryValue.UNKNOWN;
        if(n.getType() == Token.EQ || n.getType() == Token.NE) {
          result = leftTypeRestricted.testForEquality(rightTypeRestricted);
          if(n.isNE()) {
            result = result.not();
          }
        }
        else {
          if(!leftTypeRestricted.canTestForShallowEqualityWith(rightTypeRestricted)) {
            result = n.getType() == Token.SHEQ ? TernaryValue.FALSE : TernaryValue.TRUE;
          }
        }
        if(result != TernaryValue.UNKNOWN) {
          report(t, n, DETERMINISTIC_TEST, leftType.toString(), rightType.toString(), result.toString());
        }
        ensureTyped(t, n, BOOLEAN_TYPE);
        break ;
      }
      case Token.LT:
      case Token.LE:
      case Token.GT:
      case Token.GE:
      leftType = getJSType(n.getFirstChild());
      rightType = getJSType(n.getLastChild());
      if(rightType.isNumber()) {
        validator.expectNumber(t, n, leftType, "left side of numeric comparison");
      }
      else 
        if(leftType.isNumber()) {
          validator.expectNumber(t, n, rightType, "right side of numeric comparison");
        }
        else 
          if(leftType.matchesNumberContext() && rightType.matchesNumberContext()) {
          }
          else {
            String message = "left side of comparison";
            validator.expectString(t, n, leftType, message);
            validator.expectNotNullOrUndefined(t, n, leftType, message, getNativeType(STRING_TYPE));
            message = "right side of comparison";
            validator.expectString(t, n, rightType, message);
            validator.expectNotNullOrUndefined(t, n, rightType, message, getNativeType(STRING_TYPE));
          }
      ensureTyped(t, n, BOOLEAN_TYPE);
      break ;
      case Token.IN:
      left = n.getFirstChild();
      right = n.getLastChild();
      rightType = getJSType(right);
      validator.expectString(t, left, getJSType(left), "left side of \'in\'");
      validator.expectObject(t, n, rightType, "\'in\' requires an object");
      if(rightType.isStruct()) {
        report(t, right, IN_USED_WITH_STRUCT);
      }
      ensureTyped(t, n, BOOLEAN_TYPE);
      break ;
      case Token.INSTANCEOF:
      left = n.getFirstChild();
      right = n.getLastChild();
      rightType = getJSType(right).restrictByNotNullOrUndefined();
      validator.expectAnyObject(t, left, getJSType(left), "deterministic instanceof yields false");
      validator.expectActualObject(t, right, rightType, "instanceof requires an object");
      ensureTyped(t, n, BOOLEAN_TYPE);
      break ;
      case Token.ASSIGN:
      visitAssign(t, n);
      typeable = false;
      break ;
      case Token.ASSIGN_LSH:
      case Token.ASSIGN_RSH:
      case Token.ASSIGN_URSH:
      case Token.ASSIGN_DIV:
      case Token.ASSIGN_MOD:
      case Token.ASSIGN_BITOR:
      case Token.ASSIGN_BITXOR:
      case Token.ASSIGN_BITAND:
      case Token.ASSIGN_SUB:
      case Token.ASSIGN_ADD:
      case Token.ASSIGN_MUL:
      checkPropCreation(t, n.getFirstChild());
      case Token.LSH:
      case Token.RSH:
      case Token.URSH:
      case Token.DIV:
      case Token.MOD:
      case Token.BITOR:
      case Token.BITXOR:
      case Token.BITAND:
      case Token.SUB:
      case Token.ADD:
      case Token.MUL:
      visitBinaryOperator(n.getType(), t, n);
      break ;
      case Token.DELPROP:
      ensureTyped(t, n, BOOLEAN_TYPE);
      break ;
      case Token.CASE:
      JSType switchType = getJSType(parent.getFirstChild());
      JSType caseType = getJSType(n.getFirstChild());
      validator.expectSwitchMatchesCase(t, n, switchType, caseType);
      typeable = false;
      break ;
      case Token.WITH:
      {
        Node child = n.getFirstChild();
        childType = getJSType(child);
        validator.expectObject(t, child, childType, "with requires an object");
        typeable = false;
        break ;
      }
      case Token.FUNCTION:
      visitFunction(t, n);
      break ;
      case Token.LABEL:
      case Token.LABEL_NAME:
      case Token.SWITCH:
      case Token.BREAK:
      case Token.CATCH:
      case Token.TRY:
      case Token.SCRIPT:
      case Token.EXPR_RESULT:
      case Token.BLOCK:
      case Token.EMPTY:
      case Token.DEFAULT_CASE:
      case Token.CONTINUE:
      case Token.DEBUGGER:
      case Token.THROW:
      typeable = false;
      break ;
      case Token.DO:
      case Token.IF:
      case Token.WHILE:
      typeable = false;
      break ;
      case Token.FOR:
      if(NodeUtil.isForIn(n)) {
        Node obj = n.getChildAtIndex(1);
        if(getJSType(obj).isStruct()) {
          report(t, obj, IN_USED_WITH_STRUCT);
        }
      }
      typeable = false;
      break ;
      case Token.AND:
      case Token.HOOK:
      case Token.OBJECTLIT:
      case Token.OR:
      if(n.getJSType() != null) {
        ensureTyped(t, n);
      }
      else {
        if((n.isObjectLit()) && (parent.getJSType() instanceof EnumType)) {
          ensureTyped(t, n, parent.getJSType());
        }
        else {
          ensureTyped(t, n);
        }
      }
      if(n.isObjectLit()) {
        JSType typ = getJSType(n);
        for (Node key : n.children()) {
          visitObjLitKey(t, key, n, typ);
        }
      }
      break ;
      default:
      report(t, n, UNEXPECTED_TOKEN, Token.name(n.getType()));
      ensureTyped(t, n);
      break ;
    }
    typeable = typeable && !inExterns;
    if(typeable) {
      doPercentTypedAccounting(t, n);
    }
    checkNoTypeCheckSection(n, false);
  }
  private void visitAssign(NodeTraversal t, Node assign) {
    JSDocInfo info = assign.getJSDocInfo();
    Node lvalue = assign.getFirstChild();
    Node rvalue = assign.getLastChild();
    if(lvalue.isGetProp()) {
      Node object = lvalue.getFirstChild();
      JSType objectJsType = getJSType(object);
      Node property = lvalue.getLastChild();
      String pname = property.getString();
      if(object.isGetProp()) {
        JSType jsType = getJSType(object.getFirstChild());
        if(jsType.isInterface() && object.getLastChild().getString().equals("prototype")) {
          visitInterfaceGetprop(t, assign, object, pname, lvalue, rvalue);
        }
      }
      checkEnumAlias(t, info, rvalue);
      checkPropCreation(t, lvalue);
      if(pname.equals("prototype")) {
        if(objectJsType != null && objectJsType.isFunctionType()) {
          FunctionType functionType = objectJsType.toMaybeFunctionType();
          if(functionType.isConstructor()) {
            JSType rvalueType = rvalue.getJSType();
            validator.expectObject(t, rvalue, rvalueType, OVERRIDING_PROTOTYPE_WITH_NON_OBJECT);
            if(functionType.makesStructs() && !rvalueType.isStruct()) {
              String funName = functionType.getTypeOfThis().toString();
              compiler.report(t.makeError(assign, CONFLICTING_EXTENDED_TYPE, "struct", funName));
            }
            return ;
          }
        }
      }
      ObjectType type = ObjectType.cast(objectJsType.restrictByNotNullOrUndefined());
      if(type != null) {
        if(type.hasProperty(pname) && !type.isPropertyTypeInferred(pname) && !propertyIsImplicitCast(type, pname)) {
          JSType expectedType = type.getPropertyType(pname);
          if(!expectedType.isUnknownType()) {
            validator.expectCanAssignToPropertyOf(t, assign, getJSType(rvalue), expectedType, object, pname);
            checkPropertyInheritanceOnGetpropAssign(t, assign, object, pname, info, expectedType);
            return ;
          }
        }
      }
      checkPropertyInheritanceOnGetpropAssign(t, assign, object, pname, info, getNativeType(UNKNOWN_TYPE));
    }
    JSType leftType = getJSType(lvalue);
    if(lvalue.isQualifiedName()) {
      JSType rvalueType = getJSType(assign.getLastChild());
      Var var = t.getScope().getVar(lvalue.getQualifiedName());
      if(var != null) {
        if(var.isTypeInferred()) {
          return ;
        }
        if(NodeUtil.getRootOfQualifiedName(lvalue).isThis() && t.getScope() != var.getScope()) {
          return ;
        }
        if(var.getType() != null) {
          leftType = var.getType();
        }
      }
    }
    Node rightChild = assign.getLastChild();
    JSType rightType = getJSType(rightChild);
    if(validator.expectCanAssignTo(t, assign, rightType, leftType, "assignment")) {
      ensureTyped(t, assign, rightType);
    }
    else {
      ensureTyped(t, assign);
    }
  }
  private void visitBinaryOperator(int op, NodeTraversal t, Node n) {
    Node left = n.getFirstChild();
    JSType leftType = getJSType(left);
    Node right = n.getLastChild();
    JSType rightType = getJSType(right);
    switch (op){
      case Token.ASSIGN_LSH:
      case Token.ASSIGN_RSH:
      case Token.LSH:
      case Token.RSH:
      case Token.ASSIGN_URSH:
      case Token.URSH:
      if(!leftType.matchesInt32Context()) {
        report(t, left, BIT_OPERATION, NodeUtil.opToStr(n.getType()), leftType.toString());
      }
      if(!rightType.matchesUint32Context()) {
        report(t, right, BIT_OPERATION, NodeUtil.opToStr(n.getType()), rightType.toString());
      }
      break ;
      case Token.ASSIGN_DIV:
      case Token.ASSIGN_MOD:
      case Token.ASSIGN_MUL:
      case Token.ASSIGN_SUB:
      case Token.DIV:
      case Token.MOD:
      case Token.MUL:
      case Token.SUB:
      validator.expectNumber(t, left, leftType, "left operand");
      validator.expectNumber(t, right, rightType, "right operand");
      break ;
      case Token.ASSIGN_BITAND:
      case Token.ASSIGN_BITXOR:
      case Token.ASSIGN_BITOR:
      case Token.BITAND:
      case Token.BITXOR:
      case Token.BITOR:
      validator.expectBitwiseable(t, left, leftType, "bad left operand to bitwise operator");
      validator.expectBitwiseable(t, right, rightType, "bad right operand to bitwise operator");
      break ;
      case Token.ASSIGN_ADD:
      case Token.ADD:
      break ;
      default:
      report(t, n, UNEXPECTED_TOKEN, Token.name(op));
    }
    ensureTyped(t, n);
  }
  private void visitCall(NodeTraversal t, Node n) {
    Node child = n.getFirstChild();
    JSType childType = getJSType(child).restrictByNotNullOrUndefined();
    if(!childType.canBeCalled()) {
      report(t, n, NOT_CALLABLE, childType.toString());
      ensureTyped(t, n);
      return ;
    }
    if(childType.isFunctionType()) {
      FunctionType functionType = childType.toMaybeFunctionType();
      boolean isExtern = false;
      JSDocInfo functionJSDocInfo = functionType.getJSDocInfo();
      if(functionJSDocInfo != null && functionJSDocInfo.getAssociatedNode() != null) {
        isExtern = functionJSDocInfo.getAssociatedNode().isFromExterns();
      }
      if(functionType.isConstructor() && !functionType.isNativeObjectType() && (functionType.getReturnType().isUnknownType() || functionType.getReturnType().isVoidType() || !isExtern)) {
        report(t, n, CONSTRUCTOR_NOT_CALLABLE, childType.toString());
      }
      if(functionType.isOrdinaryFunction() && !functionType.getTypeOfThis().isUnknownType() && !(functionType.getTypeOfThis().toObjectType() != null && functionType.getTypeOfThis().toObjectType().isNativeObjectType()) && !(child.isGetElem() || child.isGetProp())) {
        report(t, n, EXPECTED_THIS_TYPE, functionType.toString());
      }
      visitParameterList(t, n, functionType);
      ensureTyped(t, n, functionType.getReturnType());
    }
    else {
      ensureTyped(t, n);
    }
  }
  private void visitFunction(NodeTraversal t, Node n) {
    FunctionType functionType = JSType.toMaybeFunctionType(n.getJSType());
    String functionPrivateName = n.getFirstChild().getString();
    if(functionType.isConstructor()) {
      FunctionType baseConstructor = functionType.getSuperClassConstructor();
      if(baseConstructor != getNativeType(OBJECT_FUNCTION_TYPE) && baseConstructor != null && baseConstructor.isInterface()) {
        compiler.report(t.makeError(n, CONFLICTING_EXTENDED_TYPE, "constructor", functionPrivateName));
      }
      else {
        if(baseConstructor != getNativeType(OBJECT_FUNCTION_TYPE)) {
          ObjectType proto = functionType.getPrototype();
          if(functionType.makesStructs() && !proto.isStruct()) {
            compiler.report(t.makeError(n, CONFLICTING_EXTENDED_TYPE, "struct", functionPrivateName));
          }
          else 
            if(functionType.makesDicts() && !proto.isDict()) {
              compiler.report(t.makeError(n, CONFLICTING_EXTENDED_TYPE, "dict", functionPrivateName));
            }
        }
        for (JSType baseInterface : functionType.getImplementedInterfaces()) {
          boolean badImplementedType = false;
          ObjectType baseInterfaceObj = ObjectType.cast(baseInterface);
          if(baseInterfaceObj != null) {
            FunctionType interfaceConstructor = baseInterfaceObj.getConstructor();
            if(interfaceConstructor != null && !interfaceConstructor.isInterface()) {
              badImplementedType = true;
            }
          }
          else {
            badImplementedType = true;
          }
          if(badImplementedType) {
            report(t, n, BAD_IMPLEMENTED_TYPE, functionPrivateName);
          }
        }
        validator.expectAllInterfaceProperties(t, n, functionType);
      }
    }
    else 
      if(functionType.isInterface()) {
        for (ObjectType extInterface : functionType.getExtendedInterfaces()) {
          if(extInterface.getConstructor() != null && !extInterface.getConstructor().isInterface()) {
            compiler.report(t.makeError(n, CONFLICTING_EXTENDED_TYPE, "interface", functionPrivateName));
          }
        }
        if(functionType.getExtendedInterfacesCount() > 1) {
          HashMap<String, ObjectType> properties = new HashMap<String, ObjectType>();
          HashMap<String, ObjectType> currentProperties = new HashMap<String, ObjectType>();
          for (ObjectType interfaceType : functionType.getExtendedInterfaces()) {
            currentProperties.clear();
            checkInterfaceConflictProperties(t, n, functionPrivateName, properties, currentProperties, interfaceType);
            properties.putAll(currentProperties);
          }
        }
      }
  }
  private void visitGetElem(NodeTraversal t, Node n) {
    validator.expectIndexMatch(t, n, getJSType(n.getFirstChild()), getJSType(n.getLastChild()));
    ensureTyped(t, n);
  }
  private void visitGetProp(NodeTraversal t, Node n, Node parent) {
    Node property = n.getLastChild();
    Node objNode = n.getFirstChild();
    JSType childType = getJSType(objNode);
    if(childType.isDict()) {
      report(t, property, TypeValidator.ILLEGAL_PROPERTY_ACCESS, "\'.\'", "dict");
    }
    else 
      if(validator.expectNotNullOrUndefined(t, n, childType, "No properties on this expression", getNativeType(OBJECT_TYPE))) {
        checkPropertyAccess(childType, property.getString(), t, n);
      }
    ensureTyped(t, n);
  }
  private void visitInterfaceGetprop(NodeTraversal t, Node assign, Node object, String property, Node lvalue, Node rvalue) {
    JSType rvalueType = getJSType(rvalue);
    String abstractMethodName = compiler.getCodingConvention().getAbstractMethodName();
    if(!rvalueType.isFunctionType()) {
      String abstractMethodMessage = (abstractMethodName != null) ? ", or " + abstractMethodName : "";
      compiler.report(t.makeError(object, INVALID_INTERFACE_MEMBER_DECLARATION, abstractMethodMessage));
    }
    if(assign.getLastChild().isFunction() && !NodeUtil.isEmptyBlock(assign.getLastChild().getLastChild())) {
      compiler.report(t.makeError(object, INTERFACE_FUNCTION_NOT_EMPTY, abstractMethodName));
    }
  }
  private void visitNew(NodeTraversal t, Node n) {
    Node constructor = n.getFirstChild();
    JSType type = getJSType(constructor).restrictByNotNullOrUndefined();
    if(type.isConstructor() || type.isEmptyType() || type.isUnknownType()) {
      FunctionType fnType = type.toMaybeFunctionType();
      if(fnType != null) {
        visitParameterList(t, n, fnType);
        ensureTyped(t, n, fnType.getInstanceType());
      }
      else {
        ensureTyped(t, n);
      }
    }
    else {
      report(t, n, NOT_A_CONSTRUCTOR);
      ensureTyped(t, n);
    }
  }
  private void visitObjLitKey(NodeTraversal t, Node key, Node objlit, JSType litType) {
    if(objlit.isFromExterns()) {
      ensureTyped(t, key);
      return ;
    }
    if(litType.isStruct() && key.isQuotedString()) {
      report(t, key, ILLEGAL_OBJLIT_KEY, "struct");
    }
    else 
      if(litType.isDict() && !key.isQuotedString()) {
        report(t, key, ILLEGAL_OBJLIT_KEY, "dict");
      }
    Node rvalue = key.getFirstChild();
    JSType rightType = NodeUtil.getObjectLitKeyTypeFromValueType(key, getJSType(rvalue));
    if(rightType == null) {
      rightType = getNativeType(UNKNOWN_TYPE);
    }
    Node owner = objlit;
    JSType keyType = getJSType(key);
    JSType allowedValueType = keyType;
    if(allowedValueType.isEnumElementType()) {
      allowedValueType = allowedValueType.toMaybeEnumElementType().getPrimitiveType();
    }
    boolean valid = validator.expectCanAssignToPropertyOf(t, key, rightType, allowedValueType, owner, NodeUtil.getObjectLitKeyName(key));
    if(valid) {
      ensureTyped(t, key, rightType);
    }
    else {
      ensureTyped(t, key);
    }
    JSType objlitType = getJSType(objlit);
    ObjectType type = ObjectType.cast(objlitType.restrictByNotNullOrUndefined());
    if(type != null) {
      String property = NodeUtil.getObjectLitKeyName(key);
      if(type.hasProperty(property) && !type.isPropertyTypeInferred(property) && !propertyIsImplicitCast(type, property)) {
        validator.expectCanAssignToPropertyOf(t, key, keyType, type.getPropertyType(property), owner, property);
      }
      return ;
    }
  }
  private void visitParameterList(NodeTraversal t, Node call, FunctionType functionType) {
    Iterator<Node> arguments = call.children().iterator();
    arguments.next();
    Iterator<Node> parameters = functionType.getParameters().iterator();
    int ordinal = 0;
    Node parameter = null;
    Node argument = null;
    while(arguments.hasNext() && (parameters.hasNext() || parameter != null && parameter.isVarArgs())){
      if(parameters.hasNext()) {
        parameter = parameters.next();
      }
      argument = arguments.next();
      ordinal++;
      validator.expectArgumentMatchesParameter(t, argument, getJSType(argument), getJSType(parameter), call, ordinal);
    }
    int numArgs = call.getChildCount() - 1;
    int minArgs = functionType.getMinArguments();
    int maxArgs = functionType.getMaxArguments();
    if(minArgs > numArgs || maxArgs < numArgs) {
      report(t, call, WRONG_ARGUMENT_COUNT, validator.getReadableJSTypeName(call.getFirstChild(), false), String.valueOf(numArgs), String.valueOf(minArgs), maxArgs != Integer.MAX_VALUE ? " and no more than " + maxArgs + " argument(s)" : "");
    }
  }
  private void visitReturn(NodeTraversal t, Node n) {
    JSType jsType = getJSType(t.getEnclosingFunction());
    if(jsType.isFunctionType()) {
      FunctionType functionType = jsType.toMaybeFunctionType();
      JSType returnType = functionType.getReturnType();
      if(returnType == null) {
        returnType = getNativeType(VOID_TYPE);
      }
      Node valueNode = n.getFirstChild();
      JSType actualReturnType;
      if(valueNode == null) {
        actualReturnType = getNativeType(VOID_TYPE);
        valueNode = n;
      }
      else {
        actualReturnType = getJSType(valueNode);
      }
      validator.expectCanAssignTo(t, valueNode, actualReturnType, returnType, "inconsistent return type");
    }
  }
  private void visitVar(NodeTraversal t, Node n) {
    JSDocInfo varInfo = n.hasOneChild() ? n.getJSDocInfo() : null;
    for (Node name : n.children()) {
      Node value = name.getFirstChild();
      Var var = t.getScope().getVar(name.getString());
      if(value != null) {
        JSType valueType = getJSType(value);
        JSType nameType = var.getType();
        nameType = (nameType == null) ? getNativeType(UNKNOWN_TYPE) : nameType;
        JSDocInfo info = name.getJSDocInfo();
        if(info == null) {
          info = varInfo;
        }
        checkEnumAlias(t, info, value);
        if(var.isTypeInferred()) {
          ensureTyped(t, name, valueType);
        }
        else {
          validator.expectCanAssignTo(t, value, valueType, nameType, "initializing variable");
        }
      }
    }
  }
}