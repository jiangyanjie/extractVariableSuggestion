package com.google.javascript.jscomp;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

class ProcessClosurePrimitives extends AbstractPostOrderCallback implements HotSwapCompilerPass  {
  final static DiagnosticType NULL_ARGUMENT_ERROR = DiagnosticType.error("JSC_NULL_ARGUMENT_ERROR", "method \"{0}\" called without an argument");
  final static DiagnosticType EXPECTED_OBJECTLIT_ERROR = DiagnosticType.error("JSC_EXPECTED_OBJECTLIT_ERROR", "method \"{0}\" expected an object literal argument");
  final static DiagnosticType EXPECTED_STRING_ERROR = DiagnosticType.error("JSC_EXPECTED_STRING_ERROR", "method \"{0}\" expected an object string argument");
  final static DiagnosticType INVALID_ARGUMENT_ERROR = DiagnosticType.error("JSC_INVALID_ARGUMENT_ERROR", "method \"{0}\" called with invalid argument");
  final static DiagnosticType INVALID_STYLE_ERROR = DiagnosticType.error("JSC_INVALID_CSS_NAME_MAP_STYLE_ERROR", "Invalid CSS name map style {0}");
  final static DiagnosticType TOO_MANY_ARGUMENTS_ERROR = DiagnosticType.error("JSC_TOO_MANY_ARGUMENTS_ERROR", "method \"{0}\" called with more than one argument");
  final static DiagnosticType DUPLICATE_NAMESPACE_ERROR = DiagnosticType.error("JSC_DUPLICATE_NAMESPACE_ERROR", "namespace \"{0}\" cannot be provided twice");
  final static DiagnosticType FUNCTION_NAMESPACE_ERROR = DiagnosticType.error("JSC_FUNCTION_NAMESPACE_ERROR", "\"{0}\" cannot be both provided and declared as a function");
  final static DiagnosticType MISSING_PROVIDE_ERROR = DiagnosticType.error("JSC_MISSING_PROVIDE_ERROR", "required \"{0}\" namespace never provided");
  final static DiagnosticType LATE_PROVIDE_ERROR = DiagnosticType.error("JSC_LATE_PROVIDE_ERROR", "required \"{0}\" namespace not provided yet");
  final static DiagnosticType INVALID_PROVIDE_ERROR = DiagnosticType.error("JSC_INVALID_PROVIDE_ERROR", "\"{0}\" is not a valid JS property name");
  final static DiagnosticType XMODULE_REQUIRE_ERROR = DiagnosticType.warning("JSC_XMODULE_REQUIRE_ERROR", "namespace \"{0}\" provided in module {1} " + "but required in module {2}");
  final static DiagnosticType NON_STRING_PASSED_TO_SET_CSS_NAME_MAPPING_ERROR = DiagnosticType.error("JSC_NON_STRING_PASSED_TO_SET_CSS_NAME_MAPPING_ERROR", "goog.setCssNameMapping only takes an object literal with string values");
  final static DiagnosticType INVALID_CSS_RENAMING_MAP = DiagnosticType.warning("INVALID_CSS_RENAMING_MAP", "Invalid entries in css renaming map: {0}");
  final static DiagnosticType BASE_CLASS_ERROR = DiagnosticType.error("JSC_BASE_CLASS_ERROR", "incorrect use of goog.base: {0}");
  final static String GOOG = "goog";
  final private AbstractCompiler compiler;
  final private JSModuleGraph moduleGraph;
  final private Map<String, ProvidedName> providedNames = Maps.newTreeMap();
  final private List<UnrecognizedRequire> unrecognizedRequires = Lists.newArrayList();
  final private Set<String> exportedVariables = Sets.newHashSet();
  final private CheckLevel requiresLevel;
  final private PreprocessorSymbolTable preprocessorSymbolTable;
  ProcessClosurePrimitives(AbstractCompiler compiler, @Nullable() PreprocessorSymbolTable preprocessorSymbolTable, CheckLevel requiresLevel) {
    super();
    this.compiler = compiler;
    this.preprocessorSymbolTable = preprocessorSymbolTable;
    this.moduleGraph = compiler.getModuleGraph();
    this.requiresLevel = requiresLevel;
    providedNames.put(GOOG, new ProvidedName(GOOG, null, null, false));
  }
  private Node getEnclosingDeclNameNode(NodeTraversal t) {
    Node scopeRoot = t.getScopeRoot();
    if(NodeUtil.isFunctionDeclaration(scopeRoot)) {
      return scopeRoot.getFirstChild();
    }
    else {
      Node parent = scopeRoot.getParent();
      if(parent != null) {
        if(parent.isAssign() || parent.getLastChild() == scopeRoot && parent.getFirstChild().isQualifiedName()) {
          return parent.getFirstChild();
        }
        else 
          if(parent.isName()) {
            return parent;
          }
      }
    }
    return null;
  }
  Set<String> getExportedVariableNames() {
    return exportedVariables;
  }
  private static boolean isNamespacePlaceholder(Node n) {
    if(!n.getBooleanProp(Node.IS_NAMESPACE)) {
      return false;
    }
    Node value = null;
    if(n.isExprResult()) {
      Node assign = n.getFirstChild();
      value = assign.getLastChild();
    }
    else 
      if(n.isVar()) {
        Node name = n.getFirstChild();
        value = name.getFirstChild();
      }
    return value != null && value.isObjectLit() && !value.hasChildren();
  }
  private boolean verifyArgument(NodeTraversal t, Node methodName, Node arg) {
    return verifyArgument(t, methodName, arg, Token.STRING);
  }
  private boolean verifyArgument(NodeTraversal t, Node methodName, Node arg, int desiredType) {
    DiagnosticType diagnostic = null;
    if(arg == null) {
      diagnostic = NULL_ARGUMENT_ERROR;
    }
    else 
      if(arg.getType() != desiredType) {
        diagnostic = INVALID_ARGUMENT_ERROR;
      }
      else 
        if(arg.getNext() != null) {
          diagnostic = TOO_MANY_ARGUMENTS_ERROR;
        }
    if(diagnostic != null) {
      compiler.report(t.makeError(methodName, diagnostic, methodName.getQualifiedName()));
      return false;
    }
    return true;
  }
  private boolean verifyProvide(NodeTraversal t, Node methodName, Node arg) {
    if(!verifyArgument(t, methodName, arg)) {
      return false;
    }
    for (String part : arg.getString().split("\\.")) {
      if(!NodeUtil.isValidPropertyName(part)) {
        compiler.report(t.makeError(arg, INVALID_PROVIDE_ERROR, part));
        return false;
      }
    }
    return true;
  }
  private boolean verifySetCssNameMapping(NodeTraversal t, Node methodName, Node firstArg) {
    DiagnosticType diagnostic = null;
    if(firstArg == null) {
      diagnostic = NULL_ARGUMENT_ERROR;
    }
    else 
      if(!firstArg.isObjectLit()) {
        diagnostic = EXPECTED_OBJECTLIT_ERROR;
      }
      else 
        if(firstArg.getNext() != null) {
          Node secondArg = firstArg.getNext();
          if(!secondArg.isString()) {
            diagnostic = EXPECTED_STRING_ERROR;
          }
          else 
            if(secondArg.getNext() != null) {
              diagnostic = TOO_MANY_ARGUMENTS_ERROR;
            }
        }
    if(diagnostic != null) {
      compiler.report(t.makeError(methodName, diagnostic, methodName.getQualifiedName()));
      return false;
    }
    return true;
  }
  private void handleCandidateProvideDefinition(NodeTraversal t, Node n, Node parent) {
    if(t.inGlobalScope()) {
      String name = null;
      if(n.isName() && parent.isVar()) {
        name = n.getString();
      }
      else 
        if(n.isAssign() && parent.isExprResult()) {
          name = n.getFirstChild().getQualifiedName();
        }
      if(name != null) {
        if(parent.getBooleanProp(Node.IS_NAMESPACE)) {
          processProvideFromPreviousPass(t, name, parent);
        }
        else {
          ProvidedName pn = providedNames.get(name);
          if(pn != null) {
            pn.addDefinition(parent, t.getModule());
          }
        }
      }
    }
  }
  private void handleTypedefDefinition(NodeTraversal t, Node n, Node parent) {
    Node var_1513 = n.getFirstChild();
    JSDocInfo info = var_1513.getJSDocInfo();
    if(t.inGlobalScope() && info != null && info.hasTypedefType()) {
      String name = n.getFirstChild().getQualifiedName();
      if(name != null) {
        ProvidedName pn = providedNames.get(name);
        if(pn != null) {
          pn.addDefinition(n, t.getModule());
        }
      }
    }
  }
  @Override() public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    this.compiler.process(this);
  }
  private void maybeAddStringNodeToSymbolTable(Node n) {
    if(preprocessorSymbolTable == null) {
      return ;
    }
    String name = n.getString();
    Node syntheticRef = NodeUtil.newQualifiedNameNode(compiler.getCodingConvention(), name, n, name);
    final int FOR_QUOTE = 1;
    final int FOR_DOT = 1;
    Node current = null;
    for(current = syntheticRef; current.isGetProp(); current = current.getFirstChild()) {
      int fullLen = current.getQualifiedName().length();
      int namespaceLen = current.getFirstChild().getQualifiedName().length();
      current.setSourceEncodedPosition(n.getSourcePosition() + FOR_QUOTE);
      current.setLength(fullLen);
      current.getLastChild().setSourceEncodedPosition(n.getSourcePosition() + namespaceLen + FOR_QUOTE + FOR_DOT);
      current.getLastChild().setLength(current.getLastChild().getString().length());
    }
    current.setSourceEncodedPosition(n.getSourcePosition() + FOR_QUOTE);
    current.setLength(current.getString().length());
    maybeAddToSymbolTable(syntheticRef);
  }
  private void maybeAddToSymbolTable(Node n) {
    if(preprocessorSymbolTable != null) {
      preprocessorSymbolTable.addReference(n);
    }
  }
  @Override() public void process(Node externs, Node root) {
    new NodeTraversal(compiler, this).traverse(root);
    for (ProvidedName pn : providedNames.values()) {
      pn.replace();
    }
    if(requiresLevel.isOn()) {
      for (UnrecognizedRequire r : unrecognizedRequires) {
        DiagnosticType error;
        ProvidedName expectedName = providedNames.get(r.namespace);
        if(expectedName != null && expectedName.firstNode != null) {
          error = LATE_PROVIDE_ERROR;
        }
        else {
          error = MISSING_PROVIDE_ERROR;
        }
        compiler.report(JSError.make(r.inputName, r.requireNode, requiresLevel, error, r.namespace));
      }
    }
  }
  private void processBaseClassCall(NodeTraversal t, Node n) {
    Node callee = n.getFirstChild();
    Node thisArg = callee.getNext();
    if(thisArg == null || !thisArg.isThis()) {
      reportBadBaseClassUse(t, n, "First argument must be \'this\'.");
      return ;
    }
    Node enclosingFnNameNode = getEnclosingDeclNameNode(t);
    if(enclosingFnNameNode == null) {
      reportBadBaseClassUse(t, n, "Could not find enclosing method.");
      return ;
    }
    String enclosingQname = enclosingFnNameNode.getQualifiedName();
    if(enclosingQname.indexOf(".prototype.") == -1) {
      Node enclosingParent = enclosingFnNameNode.getParent();
      Node maybeInheritsExpr = (enclosingParent.isAssign() ? enclosingParent.getParent() : enclosingParent).getNext();
      Node baseClassNode = null;
      if(maybeInheritsExpr != null && maybeInheritsExpr.isExprResult() && maybeInheritsExpr.getFirstChild().isCall()) {
        Node callNode = maybeInheritsExpr.getFirstChild();
        if("goog.inherits".equals(callNode.getFirstChild().getQualifiedName()) && callNode.getLastChild().isQualifiedName()) {
          baseClassNode = callNode.getLastChild();
        }
      }
      if(baseClassNode == null) {
        reportBadBaseClassUse(t, n, "Could not find goog.inherits for base class");
        return ;
      }
      n.replaceChild(callee, NodeUtil.newQualifiedNameNode(compiler.getCodingConvention(), String.format("%s.call", baseClassNode.getQualifiedName()), callee, "goog.base"));
      compiler.reportCodeChange();
    }
    else {
      Node methodNameNode = thisArg.getNext();
      if(methodNameNode == null || !methodNameNode.isString()) {
        reportBadBaseClassUse(t, n, "Second argument must name a method.");
        return ;
      }
      String methodName = methodNameNode.getString();
      String ending = ".prototype." + methodName;
      if(enclosingQname == null || !enclosingQname.endsWith(ending)) {
        reportBadBaseClassUse(t, n, "Enclosing method does not match " + methodName);
        return ;
      }
      Node className = enclosingFnNameNode.getFirstChild().getFirstChild();
      n.replaceChild(callee, NodeUtil.newQualifiedNameNode(compiler.getCodingConvention(), String.format("%s.superClass_.%s.call", className.getQualifiedName(), methodName), callee, "goog.base"));
      n.removeChild(methodNameNode);
      compiler.reportCodeChange();
    }
  }
  private void processProvideCall(NodeTraversal t, Node n, Node parent) {
    Node left = n.getFirstChild();
    Node arg = left.getNext();
    if(verifyProvide(t, left, arg)) {
      String ns = arg.getString();
      maybeAddToSymbolTable(left);
      maybeAddStringNodeToSymbolTable(arg);
      if(providedNames.containsKey(ns)) {
        ProvidedName previouslyProvided = providedNames.get(ns);
        if(!previouslyProvided.isExplicitlyProvided()) {
          previouslyProvided.addProvide(parent, t.getModule(), true);
        }
        else {
          compiler.report(t.makeError(n, DUPLICATE_NAMESPACE_ERROR, ns));
        }
      }
      else {
        registerAnyProvidedPrefixes(ns, parent, t.getModule());
        providedNames.put(ns, new ProvidedName(ns, parent, t.getModule(), true));
      }
    }
  }
  private void processProvideFromPreviousPass(NodeTraversal t, String name, Node parent) {
    if(!providedNames.containsKey(name)) {
      Node expr = new Node(Token.EXPR_RESULT);
      expr.copyInformationFromForTree(parent);
      parent.getParent().addChildBefore(expr, parent);
      compiler.reportCodeChange();
      JSModule module = t.getModule();
      registerAnyProvidedPrefixes(name, expr, module);
      ProvidedName provided = new ProvidedName(name, expr, module, true);
      providedNames.put(name, provided);
      provided.addDefinition(parent, module);
    }
    else {
      if(isNamespacePlaceholder(parent)) {
        parent.getParent().removeChild(parent);
        compiler.reportCodeChange();
      }
    }
  }
  private void processRequireCall(NodeTraversal t, Node n, Node parent) {
    Node left = n.getFirstChild();
    Node arg = left.getNext();
    if(verifyArgument(t, left, arg)) {
      String ns = arg.getString();
      ProvidedName provided = providedNames.get(ns);
      if(provided == null || !provided.isExplicitlyProvided()) {
        unrecognizedRequires.add(new UnrecognizedRequire(n, ns, t.getSourceName()));
      }
      else {
        JSModule providedModule = provided.explicitModule;
        Preconditions.checkNotNull(providedModule);
        JSModule module = t.getModule();
        if(moduleGraph != null && module != providedModule && !moduleGraph.dependsOn(module, providedModule)) {
          compiler.report(t.makeError(n, XMODULE_REQUIRE_ERROR, ns, providedModule.getName(), module.getName()));
        }
      }
      maybeAddToSymbolTable(left);
      maybeAddStringNodeToSymbolTable(arg);
      if(provided != null || requiresLevel.isOn()) {
        parent.detachFromParent();
        compiler.reportCodeChange();
      }
    }
  }
  private void processSetCssNameMapping(NodeTraversal t, Node n, Node parent) {
    Node left = n.getFirstChild();
    Node arg = left.getNext();
    if(verifySetCssNameMapping(t, left, arg)) {
      final Map<String, String> cssNames = Maps.newHashMap();
      for(com.google.javascript.rhino.Node key = arg.getFirstChild(); key != null; key = key.getNext()) {
        Node value = key.getFirstChild();
        if(!key.isStringKey() || value == null || !value.isString()) {
          compiler.report(t.makeError(n, NON_STRING_PASSED_TO_SET_CSS_NAME_MAPPING_ERROR));
          return ;
        }
        cssNames.put(key.getString(), value.getString());
      }
      String styleStr = "BY_PART";
      if(arg.getNext() != null) {
        styleStr = arg.getNext().getString();
      }
      final CssRenamingMap.Style style;
      try {
        style = CssRenamingMap.Style.valueOf(styleStr);
      }
      catch (IllegalArgumentException e) {
        compiler.report(t.makeError(n, INVALID_STYLE_ERROR, styleStr));
        return ;
      }
      if(style == CssRenamingMap.Style.BY_PART) {
        List<String> errors = Lists.newArrayList();
        for (String key : cssNames.keySet()) {
          if(key.contains("-")) {
            errors.add(key);
          }
        }
        if(errors.size() != 0) {
          compiler.report(t.makeError(n, INVALID_CSS_RENAMING_MAP, errors.toString()));
        }
      }
      else 
        if(style == CssRenamingMap.Style.BY_WHOLE) {
          List<String> errors = Lists.newArrayList();
          for (Map.Entry<String, String> b : cssNames.entrySet()) {
            if(b.getKey().length() > 10) 
              continue ;
            for (Map.Entry<String, String> a : cssNames.entrySet()) {
              String combined = cssNames.get(a.getKey() + "-" + b.getKey());
              if(combined != null && !combined.equals(a.getValue() + "-" + b.getValue())) {
                errors.add("map(" + a.getKey() + "-" + b.getKey() + ") != map(" + a.getKey() + ")-map(" + b.getKey() + ")");
              }
            }
          }
          if(errors.size() != 0) {
            compiler.report(t.makeError(n, INVALID_CSS_RENAMING_MAP, errors.toString()));
          }
        }
      CssRenamingMap cssRenamingMap = new CssRenamingMap() {
          @Override() public String get(String value) {
            if(cssNames.containsKey(value)) {
              return cssNames.get(value);
            }
            else {
              return value;
            }
          }
          @Override() public CssRenamingMap.Style getStyle() {
            return style;
          }
      };
      compiler.setCssRenamingMap(cssRenamingMap);
      parent.getParent().removeChild(parent);
      compiler.reportCodeChange();
    }
  }
  private void registerAnyProvidedPrefixes(String ns, Node node, JSModule module) {
    int pos = ns.indexOf('.');
    while(pos != -1){
      String prefixNs = ns.substring(0, pos);
      pos = ns.indexOf('.', pos + 1);
      if(providedNames.containsKey(prefixNs)) {
        providedNames.get(prefixNs).addProvide(node, module, false);
      }
      else {
        providedNames.put(prefixNs, new ProvidedName(prefixNs, node, module, false));
      }
    }
  }
  private void reportBadBaseClassUse(NodeTraversal t, Node n, String extraMessage) {
    compiler.report(t.makeError(n, BASE_CLASS_ERROR, extraMessage));
  }
  @Override() public void visit(NodeTraversal t, Node n, Node parent) {
    switch (n.getType()){
      case Token.CALL:
      boolean isExpr = parent.isExprResult();
      Node left = n.getFirstChild();
      if(left.isGetProp()) {
        Node name = left.getFirstChild();
        if(name.isName() && GOOG.equals(name.getString())) {
          String methodName = name.getNext().getString();
          if("base".equals(methodName)) {
            processBaseClassCall(t, n);
          }
          else 
            if(!isExpr) {
              break ;
            }
            else 
              if("require".equals(methodName)) {
                processRequireCall(t, n, parent);
              }
              else 
                if("provide".equals(methodName)) {
                  processProvideCall(t, n, parent);
                }
                else 
                  if("exportSymbol".equals(methodName)) {
                    Node arg = left.getNext();
                    if(arg.isString()) {
                      int dot = arg.getString().indexOf('.');
                      if(dot == -1) {
                        exportedVariables.add(arg.getString());
                      }
                      else {
                        exportedVariables.add(arg.getString().substring(0, dot));
                      }
                    }
                  }
                  else 
                    if("addDependency".equals(methodName)) {
                      CodingConvention convention = compiler.getCodingConvention();
                      List<String> typeDecls = convention.identifyTypeDeclarationCall(n);
                      if(typeDecls != null) {
                        for (String typeDecl : typeDecls) {
                          compiler.getTypeRegistry().forwardDeclareType(typeDecl);
                        }
                      }
                      parent.replaceChild(n, IR.number(0));
                      compiler.reportCodeChange();
                    }
                    else 
                      if("setCssNameMapping".equals(methodName)) {
                        processSetCssNameMapping(t, n, parent);
                      }
        }
      }
      break ;
      case Token.ASSIGN:
      case Token.NAME:
      handleCandidateProvideDefinition(t, n, parent);
      break ;
      case Token.EXPR_RESULT:
      handleTypedefDefinition(t, n, parent);
      break ;
      case Token.FUNCTION:
      if(t.inGlobalScope() && !NodeUtil.isFunctionExpression(n)) {
        String name = n.getFirstChild().getString();
        ProvidedName pn = providedNames.get(name);
        if(pn != null) {
          compiler.report(t.makeError(n, FUNCTION_NAMESPACE_ERROR, name));
        }
      }
      break ;
      case Token.GETPROP:
      if(n.getFirstChild().isName() && !parent.isCall() && !parent.isAssign() && "goog.base".equals(n.getQualifiedName())) {
        reportBadBaseClassUse(t, n, "May only be called directly.");
      }
      break ;
    }
  }
  
  private class ProvidedName  {
    final private String namespace;
    final private Node firstNode;
    final private JSModule firstModule;
    private Node explicitNode = null;
    private JSModule explicitModule = null;
    private Node candidateDefinition = null;
    private JSModule minimumModule = null;
    private Node replacementNode = null;
    ProvidedName(String namespace, Node node, JSModule module, boolean explicit) {
      super();
      Preconditions.checkArgument(node == null || node.isExprResult());
      this.namespace = namespace;
      this.firstNode = node;
      this.firstModule = module;
      addProvide(node, module, explicit);
    }
    private JSDocInfo createConstantJsDoc() {
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordConstancy();
      return builder.build(null);
    }
    private Node createDeclarationNode() {
      if(namespace.indexOf('.') == -1) {
        return makeVarDeclNode();
      }
      else {
        return makeAssignmentExprNode();
      }
    }
    private Node createNamespaceLiteral() {
      Node objlit = IR.objectlit();
      objlit.setJSType(compiler.getTypeRegistry().createAnonymousObjectType(null));
      return objlit;
    }
    private Node getProvideStringNode() {
      return (firstNode.getFirstChild() != null && NodeUtil.isExprCall(firstNode)) ? firstNode.getFirstChild().getLastChild() : null;
    }
    private Node makeAssignmentExprNode() {
      Node decl = IR.exprResult(IR.assign(NodeUtil.newQualifiedNameNode(compiler.getCodingConvention(), namespace, firstNode, namespace), createNamespaceLiteral()));
      decl.putBooleanProp(Node.IS_NAMESPACE, true);
      if(candidateDefinition == null) {
        decl.getFirstChild().setJSDocInfo(createConstantJsDoc());
      }
      Preconditions.checkState(isNamespacePlaceholder(decl));
      setSourceInfo(decl);
      return decl;
    }
    private Node makeVarDeclNode() {
      Node name = IR.name(namespace);
      name.addChildToFront(createNamespaceLiteral());
      Node decl = IR.var(name);
      decl.putBooleanProp(Node.IS_NAMESPACE, true);
      if(compiler.getCodingConvention().isConstant(namespace)) {
        name.putBooleanProp(Node.IS_CONSTANT_NAME, true);
      }
      if(candidateDefinition == null) {
        name.setJSDocInfo(createConstantJsDoc());
      }
      Preconditions.checkState(isNamespacePlaceholder(decl));
      setSourceInfo(decl);
      return decl;
    }
    boolean isExplicitlyProvided() {
      return explicitNode != null;
    }
    private int getSourceInfoOffset(Node provideStringNode) {
      if(provideStringNode == null) {
        return 0;
      }
      int indexOfLastDot = namespace.lastIndexOf('.');
      return 2 + indexOfLastDot;
    }
    void addDefinition(Node node, JSModule module) {
      Preconditions.checkArgument(node.isExprResult() || node.isFunction() || node.isVar());
      Preconditions.checkArgument(explicitNode != node);
      if((candidateDefinition == null) || !node.isExprResult()) {
        candidateDefinition = node;
        updateMinimumModule(module);
      }
    }
    void addProvide(Node node, JSModule module, boolean explicit) {
      if(explicit) {
        Preconditions.checkState(explicitNode == null);
        Preconditions.checkArgument(node.isExprResult());
        explicitNode = node;
        explicitModule = module;
      }
      updateMinimumModule(module);
    }
    void replace() {
      if(firstNode == null) {
        replacementNode = candidateDefinition;
        return ;
      }
      if(candidateDefinition != null && explicitNode != null) {
        explicitNode.detachFromParent();
        compiler.reportCodeChange();
        replacementNode = candidateDefinition;
        if(candidateDefinition.isExprResult() && !candidateDefinition.getFirstChild().isQualifiedName()) {
          candidateDefinition.putBooleanProp(Node.IS_NAMESPACE, true);
          Node assignNode = candidateDefinition.getFirstChild();
          Node nameNode = assignNode.getFirstChild();
          if(nameNode.isName()) {
            Node valueNode = nameNode.getNext();
            assignNode.removeChild(nameNode);
            assignNode.removeChild(valueNode);
            nameNode.addChildToFront(valueNode);
            Node varNode = IR.var(nameNode);
            varNode.copyInformationFrom(candidateDefinition);
            candidateDefinition.getParent().replaceChild(candidateDefinition, varNode);
            nameNode.setJSDocInfo(assignNode.getJSDocInfo());
            compiler.reportCodeChange();
            replacementNode = varNode;
          }
        }
      }
      else {
        replacementNode = createDeclarationNode();
        if(firstModule == minimumModule) {
          firstNode.getParent().addChildBefore(replacementNode, firstNode);
        }
        else {
          int indexOfDot = namespace.lastIndexOf('.');
          if(indexOfDot == -1) {
            compiler.getNodeForCodeInsertion(minimumModule).addChildToBack(replacementNode);
          }
          else {
            ProvidedName parentName = providedNames.get(namespace.substring(0, indexOfDot));
            Preconditions.checkNotNull(parentName);
            Preconditions.checkNotNull(parentName.replacementNode);
            parentName.replacementNode.getParent().addChildAfter(replacementNode, parentName.replacementNode);
          }
        }
        if(explicitNode != null) {
          explicitNode.detachFromParent();
        }
        compiler.reportCodeChange();
      }
    }
    private void setSourceInfo(Node newNode) {
      Node provideStringNode = getProvideStringNode();
      int offset = getSourceInfoOffset(provideStringNode);
      Node sourceInfoNode = provideStringNode == null ? firstNode : provideStringNode;
      newNode.copyInformationFromForTree(sourceInfoNode);
      if(offset != 0) {
        newNode.setSourceEncodedPositionForTree(sourceInfoNode.getSourcePosition() + offset);
      }
    }
    private void updateMinimumModule(JSModule newModule) {
      if(minimumModule == null) {
        minimumModule = newModule;
      }
      else 
        if(moduleGraph != null) {
          minimumModule = moduleGraph.getDeepestCommonDependencyInclusive(minimumModule, newModule);
        }
        else {
          Preconditions.checkState(newModule == minimumModule, "Missing module graph");
        }
    }
  }
  
  private class UnrecognizedRequire  {
    final Node requireNode;
    final String namespace;
    final String inputName;
    UnrecognizedRequire(Node requireNode, String namespace, String inputName) {
      super();
      this.requireNode = requireNode;
      this.namespace = namespace;
      this.inputName = inputName;
    }
  }
}