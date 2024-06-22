package com.google.javascript.jscomp.type;
import static com.google.javascript.rhino.jstype.JSTypeNative.UNKNOWN_TYPE;
import com.google.common.base.Function;
import com.google.javascript.jscomp.CodingConvention;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSType.TypePair;
import com.google.javascript.rhino.jstype.JSTypeNative;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.StaticSlot;
import com.google.javascript.rhino.jstype.UnionType;
import com.google.javascript.rhino.jstype.Visitor;

public class SemanticReverseAbstractInterpreter extends ChainableReverseAbstractInterpreter  {
  final private static Function<TypePair, TypePair> EQ = new Function<TypePair, TypePair>() {
      @Override() public TypePair apply(TypePair p) {
        if(p.typeA == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderEquality(p.typeB);
      }
  };
  final private static Function<TypePair, TypePair> NE = new Function<TypePair, TypePair>() {
      @Override() public TypePair apply(TypePair p) {
        if(p.typeA == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderInequality(p.typeB);
      }
  };
  final private static Function<TypePair, TypePair> SHEQ = new Function<TypePair, TypePair>() {
      @Override() public TypePair apply(TypePair p) {
        JSType var_2467 = p.typeA;
        if(var_2467 == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderShallowEquality(p.typeB);
      }
  };
  final private static Function<TypePair, TypePair> SHNE = new Function<TypePair, TypePair>() {
      @Override() public TypePair apply(TypePair p) {
        if(p.typeA == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderShallowInequality(p.typeB);
      }
  };
  final private Function<TypePair, TypePair> INEQ = new Function<TypePair, TypePair>() {
      @Override() public TypePair apply(TypePair p) {
        return new TypePair(getRestrictedWithoutUndefined(p.typeA), getRestrictedWithoutUndefined(p.typeB));
      }
  };
  public SemanticReverseAbstractInterpreter(CodingConvention convention, JSTypeRegistry typeRegistry) {
    super(convention, typeRegistry);
  }
  private FlowScope caseAndOrMaybeShortCircuiting(Node left, Node right, FlowScope blindScope, boolean condition) {
    FlowScope leftScope = firstPreciserScopeKnowingConditionOutcome(left, blindScope, !condition);
    StaticSlot<JSType> leftVar = leftScope.findUniqueRefinedSlot(blindScope);
    if(leftVar == null) {
      return blindScope;
    }
    FlowScope rightScope = firstPreciserScopeKnowingConditionOutcome(left, blindScope, condition);
    rightScope = firstPreciserScopeKnowingConditionOutcome(right, rightScope, !condition);
    StaticSlot<JSType> rightVar = rightScope.findUniqueRefinedSlot(blindScope);
    if(rightVar == null || !leftVar.getName().equals(rightVar.getName())) {
      return blindScope;
    }
    JSType type = leftVar.getType().getLeastSupertype(rightVar.getType());
    FlowScope informed = blindScope.createChildFlowScope();
    informed.inferSlotType(leftVar.getName(), type);
    return informed;
  }
  private FlowScope caseAndOrNotShortCircuiting(Node left, Node right, FlowScope blindScope, boolean condition) {
    JSType leftType = getTypeIfRefinable(left, blindScope);
    boolean leftIsRefineable;
    if(leftType != null) {
      leftIsRefineable = true;
    }
    else {
      leftIsRefineable = false;
      leftType = left.getJSType();
      blindScope = firstPreciserScopeKnowingConditionOutcome(left, blindScope, condition);
    }
    JSType restrictedLeftType = (leftType == null) ? null : leftType.getRestrictedTypeGivenToBooleanOutcome(condition);
    if(restrictedLeftType == null) {
      return firstPreciserScopeKnowingConditionOutcome(right, blindScope, condition);
    }
    JSType rightType = getTypeIfRefinable(right, blindScope);
    boolean rightIsRefineable;
    if(rightType != null) {
      rightIsRefineable = true;
    }
    else {
      rightIsRefineable = false;
      rightType = right.getJSType();
      blindScope = firstPreciserScopeKnowingConditionOutcome(right, blindScope, condition);
    }
    if(condition) {
      JSType restrictedRightType = (rightType == null) ? null : rightType.getRestrictedTypeGivenToBooleanOutcome(condition);
      return maybeRestrictTwoNames(blindScope, left, leftType, leftIsRefineable ? restrictedLeftType : null, right, rightType, rightIsRefineable ? restrictedRightType : null);
    }
    return blindScope;
  }
  private FlowScope caseEquality(Node condition, FlowScope blindScope, Function<TypePair, TypePair> merging) {
    return caseEquality(condition.getFirstChild(), condition.getLastChild(), blindScope, merging);
  }
  private FlowScope caseEquality(Node left, Node right, FlowScope blindScope, Function<TypePair, TypePair> merging) {
    JSType leftType = getTypeIfRefinable(left, blindScope);
    boolean leftIsRefineable;
    if(leftType != null) {
      leftIsRefineable = true;
    }
    else {
      leftIsRefineable = false;
      leftType = left.getJSType();
    }
    JSType rightType = getTypeIfRefinable(right, blindScope);
    boolean rightIsRefineable;
    if(rightType != null) {
      rightIsRefineable = true;
    }
    else {
      rightIsRefineable = false;
      rightType = right.getJSType();
    }
    TypePair merged = merging.apply(new TypePair(leftType, rightType));
    if(merged != null) {
      return maybeRestrictTwoNames(blindScope, left, leftType, leftIsRefineable ? merged.typeA : null, right, rightType, rightIsRefineable ? merged.typeB : null);
    }
    return blindScope;
  }
  private FlowScope caseIn(Node object, String propertyName, FlowScope blindScope) {
    JSType jsType = object.getJSType();
    jsType = this.getRestrictedWithoutNull(jsType);
    jsType = this.getRestrictedWithoutUndefined(jsType);
    boolean hasProperty = false;
    ObjectType objectType = ObjectType.cast(jsType);
    if(objectType != null) {
      hasProperty = objectType.hasProperty(propertyName);
    }
    if(!hasProperty) {
      String qualifiedName = object.getQualifiedName();
      if(qualifiedName != null) {
        String propertyQualifiedName = qualifiedName + "." + propertyName;
        if(blindScope.getSlot(propertyQualifiedName) == null) {
          FlowScope informed = blindScope.createChildFlowScope();
          JSType unknownType = typeRegistry.getNativeType(JSTypeNative.UNKNOWN_TYPE);
          informed.inferQualifiedSlot(object, propertyQualifiedName, unknownType, unknownType);
          return informed;
        }
      }
    }
    return blindScope;
  }
  private FlowScope caseInstanceOf(Node left, Node right, FlowScope blindScope, boolean outcome) {
    JSType leftType = getTypeIfRefinable(left, blindScope);
    if(leftType == null) {
      return blindScope;
    }
    JSType rightType = right.getJSType();
    ObjectType targetType = typeRegistry.getNativeObjectType(JSTypeNative.UNKNOWN_TYPE);
    if(rightType != null && rightType.isFunctionType()) {
      targetType = rightType.toMaybeFunctionType();
    }
    Visitor<JSType> visitor;
    if(outcome) {
      visitor = new RestrictByTrueInstanceOfResultVisitor(targetType);
    }
    else {
      visitor = new RestrictByFalseInstanceOfResultVisitor(targetType);
    }
    return maybeRestrictName(blindScope, left, leftType, leftType.visit(visitor));
  }
  private FlowScope caseNameOrGetProp(Node name, FlowScope blindScope, boolean outcome) {
    JSType type = getTypeIfRefinable(name, blindScope);
    if(type != null) {
      return maybeRestrictName(blindScope, name, type, type.getRestrictedTypeGivenToBooleanOutcome(outcome));
    }
    return blindScope;
  }
  private FlowScope caseTypeOf(Node node, JSType type, String value, boolean resultEqualsValue, FlowScope blindScope) {
    return maybeRestrictName(blindScope, node, type, getRestrictedByTypeOfResult(type, value, resultEqualsValue));
  }
  @Override() public FlowScope getPreciserScopeKnowingConditionOutcome(Node condition, FlowScope blindScope, boolean outcome) {
    int operatorToken = condition.getType();
    switch (operatorToken){
      case Token.EQ:
      case Token.NE:
      case Token.SHEQ:
      case Token.SHNE:
      case Token.CASE:
      Node left;
      Node right;
      if(operatorToken == Token.CASE) {
        left = condition.getParent().getFirstChild();
        right = condition.getFirstChild();
      }
      else {
        left = condition.getFirstChild();
        right = condition.getLastChild();
      }
      Node typeOfNode = null;
      Node stringNode = null;
      if(left.isTypeOf() && right.isString()) {
        typeOfNode = left;
        stringNode = right;
      }
      else 
        if(right.isTypeOf() && left.isString()) {
          typeOfNode = right;
          stringNode = left;
        }
      if(typeOfNode != null && stringNode != null) {
        Node operandNode = typeOfNode.getFirstChild();
        JSType operandType = getTypeIfRefinable(operandNode, blindScope);
        if(operandType != null) {
          boolean resultEqualsValue = operatorToken == Token.EQ || operatorToken == Token.SHEQ || operatorToken == Token.CASE;
          if(!outcome) {
            resultEqualsValue = !resultEqualsValue;
          }
          return caseTypeOf(operandNode, operandType, stringNode.getString(), resultEqualsValue, blindScope);
        }
      }
    }
    switch (operatorToken){
      case Token.AND:
      if(outcome) {
        return caseAndOrNotShortCircuiting(condition.getFirstChild(), condition.getLastChild(), blindScope, true);
      }
      else {
        return caseAndOrMaybeShortCircuiting(condition.getFirstChild(), condition.getLastChild(), blindScope, true);
      }
      case Token.OR:
      if(!outcome) {
        return caseAndOrNotShortCircuiting(condition.getFirstChild(), condition.getLastChild(), blindScope, false);
      }
      else {
        return caseAndOrMaybeShortCircuiting(condition.getFirstChild(), condition.getLastChild(), blindScope, false);
      }
      case Token.EQ:
      if(outcome) {
        return caseEquality(condition, blindScope, EQ);
      }
      else {
        return caseEquality(condition, blindScope, NE);
      }
      case Token.NE:
      if(outcome) {
        return caseEquality(condition, blindScope, NE);
      }
      else {
        return caseEquality(condition, blindScope, EQ);
      }
      case Token.SHEQ:
      if(outcome) {
        return caseEquality(condition, blindScope, SHEQ);
      }
      else {
        return caseEquality(condition, blindScope, SHNE);
      }
      case Token.SHNE:
      if(outcome) {
        return caseEquality(condition, blindScope, SHNE);
      }
      else {
        return caseEquality(condition, blindScope, SHEQ);
      }
      case Token.NAME:
      case Token.GETPROP:
      return caseNameOrGetProp(condition, blindScope, outcome);
      case Token.ASSIGN:
      return firstPreciserScopeKnowingConditionOutcome(condition.getFirstChild(), firstPreciserScopeKnowingConditionOutcome(condition.getFirstChild().getNext(), blindScope, outcome), outcome);
      case Token.NOT:
      return firstPreciserScopeKnowingConditionOutcome(condition.getFirstChild(), blindScope, !outcome);
      case Token.LE:
      case Token.LT:
      case Token.GE:
      case Token.GT:
      if(outcome) {
        return caseEquality(condition, blindScope, INEQ);
      }
      break ;
      case Token.INSTANCEOF:
      return caseInstanceOf(condition.getFirstChild(), condition.getLastChild(), blindScope, outcome);
      case Token.IN:
      if(outcome && condition.getFirstChild().isString()) {
        return caseIn(condition.getLastChild(), condition.getFirstChild().getString(), blindScope);
      }
      break ;
      case Token.CASE:
      Node left = condition.getParent().getFirstChild();
      Node right = condition.getFirstChild();
      if(outcome) {
        return caseEquality(left, right, blindScope, SHEQ);
      }
      else {
        return caseEquality(left, right, blindScope, SHNE);
      }
    }
    return nextPreciserScopeKnowingConditionOutcome(condition, blindScope, outcome);
  }
  private FlowScope maybeRestrictName(FlowScope blindScope, Node node, JSType originalType, JSType restrictedType) {
    if(restrictedType != null && restrictedType != originalType) {
      FlowScope informed = blindScope.createChildFlowScope();
      declareNameInScope(informed, node, restrictedType);
      return informed;
    }
    return blindScope;
  }
  private FlowScope maybeRestrictTwoNames(FlowScope blindScope, Node left, JSType originalLeftType, JSType restrictedLeftType, Node right, JSType originalRightType, JSType restrictedRightType) {
    boolean shouldRefineLeft = restrictedLeftType != null && restrictedLeftType != originalLeftType;
    boolean shouldRefineRight = restrictedRightType != null && restrictedRightType != originalRightType;
    if(shouldRefineLeft || shouldRefineRight) {
      FlowScope informed = blindScope.createChildFlowScope();
      if(shouldRefineLeft) {
        declareNameInScope(informed, left, restrictedLeftType);
      }
      if(shouldRefineRight) {
        declareNameInScope(informed, right, restrictedRightType);
      }
      return informed;
    }
    return blindScope;
  }
  
  private class RestrictByFalseInstanceOfResultVisitor extends RestrictByFalseTypeOfResultVisitor  {
    final private ObjectType target;
    RestrictByFalseInstanceOfResultVisitor(ObjectType target) {
      super();
      this.target = target;
    }
    @Override() public JSType caseFunctionType(FunctionType type) {
      return caseObjectType(type);
    }
    @Override() public JSType caseObjectType(ObjectType type) {
      if(target.isUnknownType()) {
        return type;
      }
      FunctionType funcTarget = target.toMaybeFunctionType();
      if(funcTarget.hasInstanceType()) {
        if(type.isSubtype(funcTarget.getInstanceType())) {
          return null;
        }
        return type;
      }
      return null;
    }
    @Override() public JSType caseUnionType(UnionType type) {
      if(target.isUnknownType()) {
        return type;
      }
      FunctionType funcTarget = target.toMaybeFunctionType();
      if(funcTarget.hasInstanceType()) {
        return type.getRestrictedUnion(funcTarget.getInstanceType());
      }
      return null;
    }
  }
  
  private class RestrictByTrueInstanceOfResultVisitor extends RestrictByTrueTypeOfResultVisitor  {
    final private ObjectType target;
    RestrictByTrueInstanceOfResultVisitor(ObjectType target) {
      super();
      this.target = target;
    }
    private JSType applyCommonRestriction(JSType type) {
      if(target.isUnknownType()) {
        return type;
      }
      FunctionType funcTarget = target.toMaybeFunctionType();
      if(funcTarget.hasInstanceType()) {
        return type.getGreatestSubtype(funcTarget.getInstanceType());
      }
      return null;
    }
    @Override() public JSType caseFunctionType(FunctionType type) {
      return caseObjectType(type);
    }
    @Override() public JSType caseObjectType(ObjectType type) {
      return applyCommonRestriction(type);
    }
    @Override() protected JSType caseTopType(JSType type) {
      return applyCommonRestriction(type);
    }
    @Override() public JSType caseUnionType(UnionType type) {
      return applyCommonRestriction(type);
    }
    @Override() public JSType caseUnknownType() {
      FunctionType funcTarget = JSType.toMaybeFunctionType(target);
      if(funcTarget != null && funcTarget.hasInstanceType()) {
        return funcTarget.getInstanceType();
      }
      return getNativeType(UNKNOWN_TYPE);
    }
  }
}