package com.google.javascript.jscomp;
import static com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.JsMessage.Builder;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.*;
import java.util.regex.*;
import javax.annotation.Nullable;

abstract class JsMessageVisitor extends AbstractPostOrderCallback implements CompilerPass  {
  final private static String MSG_FUNCTION_NAME = "goog.getMsg";
  final private static String MSG_FALLBACK_FUNCTION_NAME = "goog.getMsgWithFallback";
  final static DiagnosticType MESSAGE_HAS_NO_DESCRIPTION = DiagnosticType.warning("JSC_MSG_HAS_NO_DESCRIPTION", "Message {0} has no description. Add @desc JsDoc tag.");
  final static DiagnosticType MESSAGE_HAS_NO_TEXT = DiagnosticType.warning("JSC_MSG_HAS_NO_TEXT", "Message value of {0} is just an empty string. " + "Empty messages are forbidden.");
  final static DiagnosticType MESSAGE_TREE_MALFORMED = DiagnosticType.error("JSC_MSG_TREE_MALFORMED", "Message parse tree malformed. {0}");
  final static DiagnosticType MESSAGE_HAS_NO_VALUE = DiagnosticType.error("JSC_MSG_HAS_NO_VALUE", "message node {0} has no value");
  final static DiagnosticType MESSAGE_DUPLICATE_KEY = DiagnosticType.error("JSC_MSG_KEY_DUPLICATED", "duplicate message variable name found for {0}, " + "initial definition {1}:{2}");
  final static DiagnosticType MESSAGE_NODE_IS_ORPHANED = DiagnosticType.warning("JSC_MSG_ORPHANED_NODE", MSG_FUNCTION_NAME + "() function could be used only with MSG_* property or variable");
  final static DiagnosticType MESSAGE_NOT_INITIALIZED_USING_NEW_SYNTAX = DiagnosticType.error("JSC_MSG_NOT_INITIALIZED_USING_NEW_SYNTAX", "message not initialized using " + MSG_FUNCTION_NAME);
  final static DiagnosticType BAD_FALLBACK_SYNTAX = DiagnosticType.error("JSC_MSG_BAD_FALLBACK_SYNTAX", String.format("Bad syntax. " + "Expected syntax: goog.getMsgWithFallback(MSG_1, MSG_2)", MSG_FALLBACK_FUNCTION_NAME));
  final static DiagnosticType FALLBACK_ARG_ERROR = DiagnosticType.error("JSC_MSG_FALLBACK_ARG_ERROR", "Could not find message entry for fallback argument {0}");
  final private static String PH_JS_PREFIX = "{$";
  final private static String PH_JS_SUFFIX = "}";
  final static String MSG_PREFIX = "MSG_";
  final private static Pattern MSG_UNNAMED_PATTERN = Pattern.compile("MSG_UNNAMED_\\d+");
  final private static Pattern CAMELCASE_PATTERN = Pattern.compile("[a-z][a-zA-Z\\d]*[_\\d]*");
  final static String HIDDEN_DESC_PREFIX = "@hidden";
  final private static String DESC_SUFFIX = "_HELP";
  final private boolean needToCheckDuplications;
  final private JsMessage.Style style;
  final private JsMessage.IdGenerator idGenerator;
  final AbstractCompiler compiler;
  final private Map<String, MessageLocation> messageNames = Maps.newHashMap();
  final private Map<Var, JsMessage> unnamedMessages = Maps.newHashMap();
  final private Map<Node, String> googMsgNodes = Maps.newHashMap();
  final private CheckLevel checkLevel;
  JsMessageVisitor(AbstractCompiler compiler, boolean needToCheckDuplications, JsMessage.Style style, JsMessage.IdGenerator idGenerator) {
    super();
    this.compiler = compiler;
    this.needToCheckDuplications = needToCheckDuplications;
    this.style = style;
    this.idGenerator = idGenerator;
    checkLevel = (style == JsMessage.Style.CLOSURE) ? CheckLevel.ERROR : CheckLevel.WARNING;
  }
  private JsMessage getTrackedMessage(NodeTraversal t, String msgName) {
    boolean isUnnamedMessage = isUnnamedMessageName(msgName);
    if(!isUnnamedMessage) {
      MessageLocation location = messageNames.get(msgName);
      return location == null ? null : location.message;
    }
    else {
      Var var = t.getScope().getVar(msgName);
      if(var != null) {
        return unnamedMessages.get(var);
      }
    }
    return null;
  }
  private static String extractStringFromStringExprNode(Node node) throws MalformedException {
    switch (node.getType()){
      case Token.STRING:
      return node.getString();
      case Token.ADD:
      StringBuilder sb = new StringBuilder();
      for (Node child : node.children()) {
        sb.append(extractStringFromStringExprNode(child));
      }
      return sb.toString();
      default:
      throw new MalformedException("STRING or ADD node expected; found: " + getReadableTokenName(node), node);
    }
  }
  private static String getReadableTokenName(Node node) {
    return Token.name(node.getType());
  }
  static String toLowerCamelCaseWithNumericSuffixes(String input) {
    int suffixStart = input.length();
    while(suffixStart > 0){
      char ch = '\u0000';
      int numberStart = suffixStart;
      while(numberStart > 0){
        ch = input.charAt(numberStart - 1);
        if(Character.isDigit(ch)) {
          numberStart--;
        }
        else {
          break ;
        }
      }
      if((numberStart > 0) && (numberStart < suffixStart) && (ch == '_')) {
        suffixStart = numberStart - 1;
      }
      else {
        break ;
      }
    }
    if(suffixStart == input.length()) {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, input);
    }
    else {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, input.substring(0, suffixStart)) + input.substring(suffixStart);
    }
  }
  static boolean isLowerCamelCaseWithNumericSuffixes(String input) {
    return CAMELCASE_PATTERN.matcher(input).matches();
  }
  boolean isMessageName(String identifier, boolean isNewStyleMessage) {
    return identifier.startsWith(MSG_PREFIX) && (style == JsMessage.Style.CLOSURE || isNewStyleMessage || !identifier.endsWith(DESC_SUFFIX));
  }
  private static boolean isUnnamedMessageName(String identifier) {
    return MSG_UNNAMED_PATTERN.matcher(identifier).matches();
  }
  private boolean maybeInitMetaDataFromHelpVar(Builder builder, @Nullable() Node sibling) throws MalformedException {
    if((sibling != null) && (sibling.isVar())) {
      Node nameNode = sibling.getFirstChild();
      String name = nameNode.getString();
      if(name.equals(builder.getKey() + DESC_SUFFIX)) {
        Node valueNode = nameNode.getFirstChild();
        String desc = extractStringFromStringExprNode(valueNode);
        if(desc.startsWith(HIDDEN_DESC_PREFIX)) {
          builder.setDesc(desc.substring(HIDDEN_DESC_PREFIX.length()).trim());
          builder.setIsHidden(true);
        }
        else {
          builder.setDesc(desc);
        }
        return true;
      }
    }
    return false;
  }
  private boolean maybeInitMetaDataFromJsDoc(Builder builder, Node node) {
    boolean messageHasDesc = false;
    JSDocInfo info = node.getJSDocInfo();
    if(info != null) {
      String desc = info.getDescription();
      if(desc != null) {
        builder.setDesc(desc);
        messageHasDesc = true;
      }
      if(info.isHidden()) {
        builder.setIsHidden(true);
      }
      if(info.getMeaning() != null) {
        builder.setMeaning(info.getMeaning());
      }
    }
    return messageHasDesc;
  }
  private void checkIfMessageDuplicated(String msgName, Node msgNode) {
    if(messageNames.containsKey(msgName)) {
      MessageLocation location = messageNames.get(msgName);
      compiler.report(JSError.make(msgNode, MESSAGE_DUPLICATE_KEY, msgName, location.messageNode.getSourceFileName(), Integer.toString(location.messageNode.getLineno())));
    }
  }
  protected void checkNode(@Nullable() Node node, int type) throws MalformedException {
    if(node == null) {
      throw new MalformedException("Expected node type " + type + "; found: null", node);
    }
    int var_354 = node.getType();
    if(var_354 != type) {
      throw new MalformedException("Expected node type " + type + "; found: " + node.getType(), node);
    }
  }
  private void extractFromCallNode(Builder builder, Node node) throws MalformedException {
    if(!node.isCall()) {
      throw new MalformedException("Message must be initialized using " + MSG_FUNCTION_NAME + " function.", node);
    }
    Node fnNameNode = node.getFirstChild();
    if(!MSG_FUNCTION_NAME.equals(fnNameNode.getQualifiedName())) {
      throw new MalformedException("Message initialized using unrecognized function. " + "Please use " + MSG_FUNCTION_NAME + "() instead.", fnNameNode);
    }
    Node stringLiteralNode = fnNameNode.getNext();
    if(stringLiteralNode == null) {
      throw new MalformedException("Message string literal expected", stringLiteralNode);
    }
    parseMessageTextNode(builder, stringLiteralNode);
    Node objLitNode = stringLiteralNode.getNext();
    Set<String> phNames = Sets.newHashSet();
    if(objLitNode != null) {
      if(!objLitNode.isObjectLit()) {
        throw new MalformedException("OBJLIT node expected", objLitNode);
      }
      for(com.google.javascript.rhino.Node aNode = objLitNode.getFirstChild(); aNode != null; aNode = aNode.getNext()) {
        if(!aNode.isStringKey()) {
          throw new MalformedException("STRING_KEY node expected as OBJLIT key", aNode);
        }
        String phName = aNode.getString();
        if(!isLowerCamelCaseWithNumericSuffixes(phName)) {
          throw new MalformedException("Placeholder name not in lowerCamelCase: " + phName, aNode);
        }
        if(phNames.contains(phName)) {
          throw new MalformedException("Duplicate placeholder name: " + phName, aNode);
        }
        phNames.add(phName);
      }
    }
    Set<String> usedPlaceholders = builder.getPlaceholders();
    for (String phName : usedPlaceholders) {
      if(!phNames.contains(phName)) {
        throw new MalformedException("Unrecognized message placeholder referenced: " + phName, objLitNode);
      }
    }
    for (String phName : phNames) {
      if(!usedPlaceholders.contains(phName)) {
        throw new MalformedException("Unused message placeholder: " + phName, objLitNode);
      }
    }
  }
  private void extractFromFunctionNode(Builder builder, Node node) throws MalformedException {
    Set<String> phNames = Sets.newHashSet();
    for (Node fnChild : node.children()) {
      switch (fnChild.getType()){
        case Token.NAME:
        break ;
        case Token.PARAM_LIST:
        for (Node argumentNode : fnChild.children()) {
          if(argumentNode.isName()) {
            String phName = argumentNode.getString();
            if(phNames.contains(phName)) {
              throw new MalformedException("Duplicate placeholder name: " + phName, argumentNode);
            }
            else {
              phNames.add(phName);
            }
          }
        }
        break ;
        case Token.BLOCK:
        Node returnNode = fnChild.getFirstChild();
        if(!returnNode.isReturn()) {
          throw new MalformedException("RETURN node expected; found: " + getReadableTokenName(returnNode), returnNode);
        }
        for (Node child : returnNode.children()) {
          extractFromReturnDescendant(builder, child);
        }
        for (String phName : builder.getPlaceholders()) {
          if(!phNames.contains(phName)) {
            throw new MalformedException("Unrecognized message placeholder referenced: " + phName, returnNode);
          }
        }
        break ;
        default:
        throw new MalformedException("NAME, LP, or BLOCK node expected; found: " + getReadableTokenName(node), fnChild);
      }
    }
  }
  private void extractFromReturnDescendant(Builder builder, Node node) throws MalformedException {
    switch (node.getType()){
      case Token.STRING:
      builder.appendStringPart(node.getString());
      break ;
      case Token.NAME:
      builder.appendPlaceholderReference(node.getString());
      break ;
      case Token.ADD:
      for (Node child : node.children()) {
        extractFromReturnDescendant(builder, child);
      }
      break ;
      default:
      throw new MalformedException("STRING, NAME, or ADD node expected; found: " + getReadableTokenName(node), node);
    }
  }
  private void extractMessageFromProperty(Builder builder, Node getPropNode, Node assignNode) throws MalformedException {
    Node callNode = getPropNode.getNext();
    maybeInitMetaDataFromJsDoc(builder, assignNode);
    extractFromCallNode(builder, callNode);
  }
  private void extractMessageFromVariable(Builder builder, Node nameNode, Node parentNode, @Nullable() Node grandParentNode) throws MalformedException {
    Node valueNode = nameNode.getFirstChild();
    switch (valueNode.getType()){
      case Token.STRING:
      case Token.ADD:
      maybeInitMetaDataFromJsDocOrHelpVar(builder, parentNode, grandParentNode);
      builder.appendStringPart(extractStringFromStringExprNode(valueNode));
      break ;
      case Token.FUNCTION:
      maybeInitMetaDataFromJsDocOrHelpVar(builder, parentNode, grandParentNode);
      extractFromFunctionNode(builder, valueNode);
      break ;
      case Token.CALL:
      maybeInitMetaDataFromJsDoc(builder, parentNode);
      extractFromCallNode(builder, valueNode);
      break ;
      default:
      throw new MalformedException("Cannot parse value of message " + builder.getKey(), valueNode);
    }
  }
  private void maybeInitMetaDataFromJsDocOrHelpVar(Builder builder, Node varNode, @Nullable() Node parentOfVarNode) throws MalformedException {
    if(maybeInitMetaDataFromJsDoc(builder, varNode)) {
      return ;
    }
    if((parentOfVarNode != null) && maybeInitMetaDataFromHelpVar(builder, parentOfVarNode.getChildBefore(varNode))) {
      return ;
    }
    maybeInitMetaDataFromHelpVar(builder, varNode.getNext());
  }
  private void parseMessageTextNode(Builder builder, Node node) throws MalformedException {
    String value = extractStringFromStringExprNode(node);
    while(true){
      int phBegin = value.indexOf(PH_JS_PREFIX);
      if(phBegin < 0) {
        builder.appendStringPart(value);
        return ;
      }
      else {
        if(phBegin > 0) {
          builder.appendStringPart(value.substring(0, phBegin));
        }
        int phEnd = value.indexOf(PH_JS_SUFFIX, phBegin);
        if(phEnd < 0) {
          throw new MalformedException("Placeholder incorrectly formatted in: " + builder.getKey(), node);
        }
        String phName = value.substring(phBegin + PH_JS_PREFIX.length(), phEnd);
        builder.appendPlaceholderReference(phName);
        int nextPos = phEnd + PH_JS_SUFFIX.length();
        if(nextPos < value.length()) {
          value = value.substring(nextPos);
        }
        else {
          return ;
        }
      }
    }
  }
  @Override() public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, this);
    for (Map.Entry<Node, String> msgNode : googMsgNodes.entrySet()) {
      compiler.report(JSError.make(msgNode.getValue(), msgNode.getKey(), checkLevel, MESSAGE_NODE_IS_ORPHANED));
    }
  }
  abstract void processJsMessage(JsMessage message, JsMessageDefinition definition);
  void processMessageFallback(Node callNode, JsMessage message1, JsMessage message2) {
  }
  private void trackMessage(NodeTraversal t, JsMessage message, String msgName, Node msgNode, boolean isUnnamedMessage) {
    if(!isUnnamedMessage) {
      MessageLocation location = new MessageLocation(message, msgNode);
      messageNames.put(msgName, location);
    }
    else 
      if(msgNode.isName()) {
        Var var = t.getScope().getVar(msgName);
        if(var != null) {
          unnamedMessages.put(var, message);
        }
      }
  }
  @Override() public void visit(NodeTraversal traversal, Node node, Node parent) {
    String messageKey;
    boolean isVar;
    Node msgNode;
    Node msgNodeParent;
    switch (node.getType()){
      case Token.NAME:
      if((parent != null) && (parent.isVar())) {
        messageKey = node.getString();
        isVar = true;
      }
      else {
        return ;
      }
      msgNode = node.getFirstChild();
      msgNodeParent = node;
      break ;
      case Token.ASSIGN:
      isVar = false;
      Node getProp = node.getFirstChild();
      if(!getProp.isGetProp()) {
        return ;
      }
      Node propNode = getProp.getLastChild();
      messageKey = propNode.getString();
      msgNode = node.getLastChild();
      msgNodeParent = node;
      break ;
      case Token.CALL:
      String fnName = node.getFirstChild().getQualifiedName();
      if(MSG_FUNCTION_NAME.equals(fnName)) {
        googMsgNodes.put(node, traversal.getSourceName());
      }
      else 
        if(MSG_FALLBACK_FUNCTION_NAME.equals(fnName)) {
          visitFallbackFunctionCall(traversal, node);
        }
      return ;
      default:
      return ;
    }
    boolean isNewStyleMessage = msgNode != null && msgNode.isCall();
    if(!isMessageName(messageKey, isNewStyleMessage)) {
      return ;
    }
    if(msgNode == null) {
      compiler.report(traversal.makeError(node, MESSAGE_HAS_NO_VALUE, messageKey));
      return ;
    }
    if(isNewStyleMessage) {
      googMsgNodes.remove(msgNode);
    }
    else 
      if(style != JsMessage.Style.LEGACY) {
        compiler.report(traversal.makeError(node, checkLevel, MESSAGE_NOT_INITIALIZED_USING_NEW_SYNTAX));
      }
    boolean isUnnamedMsg = isUnnamedMessageName(messageKey);
    Builder builder = new Builder(isUnnamedMsg ? null : messageKey);
    builder.setSourceName(traversal.getSourceName());
    try {
      if(isVar) {
        extractMessageFromVariable(builder, node, parent, parent.getParent());
      }
      else {
        extractMessageFromProperty(builder, node.getFirstChild(), node);
      }
    }
    catch (MalformedException ex) {
      compiler.report(traversal.makeError(ex.getNode(), MESSAGE_TREE_MALFORMED, ex.getMessage()));
      return ;
    }
    JsMessage extractedMessage = builder.build(idGenerator);
    if(needToCheckDuplications && !isUnnamedMsg && !extractedMessage.isExternal()) {
      checkIfMessageDuplicated(messageKey, msgNode);
    }
    trackMessage(traversal, extractedMessage, messageKey, msgNode, isUnnamedMsg);
    if(extractedMessage.isEmpty()) {
      compiler.report(traversal.makeError(node, MESSAGE_HAS_NO_TEXT, messageKey));
    }
    String desc = extractedMessage.getDesc();
    if(isNewStyleMessage && (desc == null || desc.trim().isEmpty()) && !extractedMessage.isExternal()) {
      compiler.report(traversal.makeError(node, MESSAGE_HAS_NO_DESCRIPTION, messageKey));
    }
    JsMessageDefinition msgDefinition = new JsMessageDefinition(node, msgNode, msgNodeParent);
    processJsMessage(extractedMessage, msgDefinition);
  }
  private void visitFallbackFunctionCall(NodeTraversal t, Node call) {
    if(call.getChildCount() != 3 || !call.getChildAtIndex(1).isName() || !call.getChildAtIndex(2).isName()) {
      compiler.report(t.makeError(call, BAD_FALLBACK_SYNTAX));
      return ;
    }
    Node firstArg = call.getChildAtIndex(1);
    JsMessage firstMessage = getTrackedMessage(t, firstArg.getString());
    if(firstMessage == null) {
      compiler.report(t.makeError(firstArg, FALLBACK_ARG_ERROR, firstArg.getString()));
      return ;
    }
    Node secondArg = firstArg.getNext();
    JsMessage secondMessage = getTrackedMessage(t, call.getChildAtIndex(2).getString());
    if(secondMessage == null) {
      compiler.report(t.makeError(secondArg, FALLBACK_ARG_ERROR, secondArg.getString()));
      return ;
    }
    processMessageFallback(call, firstMessage, secondMessage);
  }
  
  static class MalformedException extends Exception  {
    final private static long serialVersionUID = 1L;
    final private Node node;
    MalformedException(String message, Node node) {
      super(message);
      this.node = node;
    }
    Node getNode() {
      return node;
    }
  }
  
  private static class MessageLocation  {
    final private JsMessage message;
    final private Node messageNode;
    private MessageLocation(JsMessage message, Node messageNode) {
      super();
      this.message = message;
      this.messageNode = messageNode;
    }
  }
}