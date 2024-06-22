package com.google.javascript.rhino.jstype;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.javascript.rhino.ErrorReporter;
import com.google.javascript.rhino.Node;
import java.util.List;

class NamedType extends ProxyObjectType  {
  final private static long serialVersionUID = 1L;
  final private String reference;
  final private String sourceName;
  final private int lineno;
  final private int charno;
  private Predicate<JSType> validator;
  private List<PropertyContinuation> propertyContinuations = null;
  NamedType(JSTypeRegistry registry, String reference, String sourceName, int lineno, int charno) {
    super(registry, registry.getNativeObjectType(JSTypeNative.UNKNOWN_TYPE));
    Preconditions.checkNotNull(reference);
    this.reference = reference;
    this.sourceName = sourceName;
    this.lineno = lineno;
    this.charno = charno;
  }
  public JSType getReferencedType() {
    return getReferencedTypeInternal();
  }
  JSType getTypedefType(ErrorReporter t, StaticSlot<JSType> slot, String name) {
    JSType type = slot.getType();
    if(type != null) {
      return type;
    }
    handleUnresolvedType(t, true);
    return null;
  }
  private JSType lookupViaProperties(ErrorReporter t, StaticScope<JSType> enclosing) {
    String[] componentNames = reference.split("\\.", -1);
    if(componentNames[0].length() == 0) {
      return null;
    }
    StaticSlot<JSType> slot = enclosing.getSlot(componentNames[0]);
    if(slot == null) {
      return null;
    }
    JSType slotType = slot.getType();
    if(slotType == null || slotType.isAllType() || slotType.isNoType()) {
      return null;
    }
    JSType value = getTypedefType(t, slot, componentNames[0]);
    if(value == null) {
      return null;
    }
    for(int i = 1; i < componentNames.length; i++) {
      ObjectType parentClass = ObjectType.cast(value);
      if(parentClass == null) {
        return null;
      }
      if(componentNames[i].length() == 0) {
        return null;
      }
      value = parentClass.getPropertyType(componentNames[i]);
    }
    return value;
  }
  @Override() JSType resolveInternal(ErrorReporter t, StaticScope<JSType> enclosing) {
    boolean resolved = resolveViaRegistry(t, enclosing);
    if(detectInheritanceCycle()) {
      handleTypeCycle(t);
    }
    if(resolved) {
      super.resolveInternal(t, enclosing);
      finishPropertyContinuations();
      boolean var_2641 = registry.isLastGeneration();
      return var_2641 ? getReferencedType() : this;
    }
    resolveViaProperties(t, enclosing);
    if(detectInheritanceCycle()) {
      handleTypeCycle(t);
    }
    super.resolveInternal(t, enclosing);
    if(isResolved()) {
      finishPropertyContinuations();
    }
    return registry.isLastGeneration() ? getReferencedType() : this;
  }
  @Override() public String getReferenceName() {
    return reference;
  }
  @Override() String toStringHelper(boolean forAnnotations) {
    return reference;
  }
  @Override() boolean defineProperty(String propertyName, JSType type, boolean inferred, Node propertyNode) {
    if(!isResolved()) {
      if(propertyContinuations == null) {
        propertyContinuations = Lists.newArrayList();
      }
      propertyContinuations.add(new PropertyContinuation(propertyName, type, inferred, propertyNode));
      return true;
    }
    else {
      return super.defineProperty(propertyName, type, inferred, propertyNode);
    }
  }
  @Override() public boolean hasReferenceName() {
    return true;
  }
  @Override() boolean isNamedType() {
    return true;
  }
  @Override() public boolean isNominalType() {
    return true;
  }
  private boolean resolveViaRegistry(ErrorReporter t, StaticScope<JSType> enclosing) {
    JSType type = registry.getType(reference);
    if(type != null) {
      setReferencedAndResolvedType(type, t, enclosing);
      return true;
    }
    return false;
  }
  @Override() public boolean setValidator(Predicate<JSType> validator) {
    if(this.isResolved()) {
      return super.setValidator(validator);
    }
    else {
      this.validator = validator;
      return true;
    }
  }
  @Override() public int hashCode() {
    return reference.hashCode();
  }
  private void checkEnumElementCycle(ErrorReporter t) {
    JSType referencedType = getReferencedType();
    if(referencedType instanceof EnumElementType && ((EnumElementType)referencedType).getPrimitiveType() == this) {
      handleTypeCycle(t);
    }
  }
  private void checkProtoCycle(ErrorReporter t) {
    JSType referencedType = getReferencedType();
    if(referencedType == this) {
      handleTypeCycle(t);
    }
  }
  private void finishPropertyContinuations() {
    ObjectType referencedObjType = getReferencedObjTypeInternal();
    if(referencedObjType != null && !referencedObjType.isUnknownType()) {
      if(propertyContinuations != null) {
        for (PropertyContinuation c : propertyContinuations) {
          c.commit(this);
        }
      }
    }
    propertyContinuations = null;
  }
  private void handleTypeCycle(ErrorReporter t) {
    setReferencedType(registry.getNativeObjectType(JSTypeNative.UNKNOWN_TYPE));
    t.warning("Cycle detected in inheritance chain of type " + reference, sourceName, lineno, charno);
    setResolvedTypeInternal(getReferencedType());
  }
  private void handleUnresolvedType(ErrorReporter t, boolean ignoreForwardReferencedTypes) {
    if(registry.isLastGeneration()) {
      boolean isForwardDeclared = ignoreForwardReferencedTypes && registry.isForwardDeclaredType(reference);
      if(!isForwardDeclared && registry.isLastGeneration()) {
        t.warning("Bad type annotation. Unknown type " + reference, sourceName, lineno, charno);
      }
      else {
        setReferencedType(registry.getNativeObjectType(JSTypeNative.NO_RESOLVED_TYPE));
        if(registry.isLastGeneration() && validator != null) {
          validator.apply(getReferencedType());
        }
      }
      setResolvedTypeInternal(getReferencedType());
    }
    else {
      setResolvedTypeInternal(this);
    }
  }
  private void resolveViaProperties(ErrorReporter t, StaticScope<JSType> enclosing) {
    JSType value = lookupViaProperties(t, enclosing);
    if(value != null && value.isFunctionType() && (value.isConstructor() || value.isInterface())) {
      FunctionType functionType = value.toMaybeFunctionType();
      setReferencedAndResolvedType(functionType.getInstanceType(), t, enclosing);
    }
    else 
      if(value != null && value.isNoObjectType()) {
        setReferencedAndResolvedType(registry.getNativeFunctionType(JSTypeNative.NO_OBJECT_TYPE).getInstanceType(), t, enclosing);
      }
      else 
        if(value instanceof EnumType) {
          setReferencedAndResolvedType(((EnumType)value).getElementsType(), t, enclosing);
        }
        else {
          handleUnresolvedType(t, value == null || value.isUnknownType());
        }
  }
  private void setReferencedAndResolvedType(JSType type, ErrorReporter t, StaticScope<JSType> enclosing) {
    if(validator != null) {
      validator.apply(type);
    }
    setReferencedType(type);
    checkEnumElementCycle(t);
    checkProtoCycle(t);
    setResolvedTypeInternal(getReferencedType());
  }
  
  final private static class PropertyContinuation  {
    final private String propertyName;
    final private JSType type;
    final private boolean inferred;
    final private Node propertyNode;
    private PropertyContinuation(String propertyName, JSType type, boolean inferred, Node propertyNode) {
      super();
      this.propertyName = propertyName;
      this.type = type;
      this.inferred = inferred;
      this.propertyNode = propertyNode;
    }
    void commit(ObjectType target) {
      target.defineProperty(propertyName, type, inferred, propertyNode);
    }
  }
}