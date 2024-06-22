package com.google.javascript.jscomp;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.CodingConvention.Bind;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.TernaryValue;
import java.util.regex.Pattern;

class PeepholeSubstituteAlternateSyntax extends AbstractPeepholeOptimization  {
  final private static int AND_PRECEDENCE = NodeUtil.precedence(Token.AND);
  final private static int OR_PRECEDENCE = NodeUtil.precedence(Token.OR);
  final private static int NOT_PRECEDENCE = NodeUtil.precedence(Token.NOT);
  final private static CodeGenerator REGEXP_ESCAPER = CodeGenerator.forCostEstimation(null);
  final private boolean late;
  final private int STRING_SPLIT_OVERHEAD = ".split(\'.\')".length();
  final static DiagnosticType INVALID_REGULAR_EXPRESSION_FLAGS = DiagnosticType.warning("JSC_INVALID_REGULAR_EXPRESSION_FLAGS", "Invalid flags to RegExp constructor: {0}");
  final static Predicate<Node> DONT_TRAVERSE_FUNCTIONS_PREDICATE = new Predicate<Node>() {
      @Override() public boolean apply(Node input) {
        return !input.isFunction();
      }
  };
  final private static ImmutableSet<String> STANDARD_OBJECT_CONSTRUCTORS = ImmutableSet.of("Object", "Array", "RegExp", "Error");
  final private static Pattern REGEXP_FLAGS_RE = Pattern.compile("^[gmi]*$");
  PeepholeSubstituteAlternateSyntax(boolean late) {
    super();
    this.late = late;
  }
  private FoldArrayAction isSafeToFoldArrayConstructor(Node arg) {
    FoldArrayAction action = FoldArrayAction.NOT_SAFE_TO_FOLD;
    if(arg == null) {
      action = FoldArrayAction.SAFE_TO_FOLD_WITHOUT_ARGS;
    }
    else 
      if(arg.getNext() != null) {
        action = FoldArrayAction.SAFE_TO_FOLD_WITH_ARGS;
      }
      else {
        switch (arg.getType()){
          case Token.STRING:
          action = FoldArrayAction.SAFE_TO_FOLD_WITH_ARGS;
          break ;
          case Token.NUMBER:
          if(arg.getDouble() == 0) {
            action = FoldArrayAction.SAFE_TO_FOLD_WITHOUT_ARGS;
          }
          break ;
          case Token.ARRAYLIT:
          action = FoldArrayAction.SAFE_TO_FOLD_WITH_ARGS;
          break ;
          default:
        }
      }
    return action;
  }
  private Node getBlockExpression(Node n) {
    Preconditions.checkState(isFoldableExpressBlock(n));
    return n.getFirstChild();
  }
  private Node getBlockReturnExpression(Node n) {
    Preconditions.checkState(isReturnExpressBlock(n));
    return n.getFirstChild().getFirstChild();
  }
  private Node getBlockVar(Node n) {
    Preconditions.checkState(isVarBlock(n));
    return n.getFirstChild();
  }
  Node getExceptionHandler(Node n) {
    return ControlFlowAnalysis.getExceptionHandler(n);
  }
  private static Node makeForwardSlashBracketSafe(Node n) {
    String s = n.getString();
    StringBuilder sb = null;
    int pos = 0;
    boolean isEscaped = false;
    boolean inCharset = false;
    for(int i = 0; i < s.length(); ++i) {
      char ch = s.charAt(i);
      switch (ch){
        case '\\':
        isEscaped = !isEscaped;
        continue ;
        case '/':
        if(!isEscaped && !inCharset) {
          if(null == sb) {
            sb = new StringBuilder(s.length() + 16);
          }
          sb.append(s, pos, i).append('\\');
          pos = i;
        }
        break ;
        case '[':
        if(!isEscaped) {
          inCharset = true;
        }
        break ;
        case ']':
        if(!isEscaped) {
          inCharset = false;
        }
        break ;
        case '\r':
        case '\n':
        case '\u2028':
        case '\u2029':
        if(null == sb) {
          sb = new StringBuilder(s.length() + 16);
        }
        if(isEscaped) {
          sb.append(s, pos, i - 1);
        }
        else {
          sb.append(s, pos, i);
        }
        switch (ch){
          case '\r':
          sb.append("\\r");
          break ;
          case '\n':
          sb.append("\\n");
          break ;
          case '\u2028':
          sb.append("\\u2028");
          break ;
          case '\u2029':
          sb.append("\\u2029");
          break ;
        }
        pos = i + 1;
        break ;
      }
      isEscaped = false;
    }
    if(null == sb) {
      return n.cloneTree();
    }
    sb.append(s, pos, s.length());
    return IR.string(sb.toString()).srcref(n);
  }
  private Node maybeReplaceChildWithNumber(Node n, Node parent, int num) {
    Node newNode = IR.number(num);
    if(!newNode.isEquivalentTo(n)) {
      parent.replaceChild(n, newNode);
      reportCodeChange();
      return newNode;
    }
    return n;
  }
  @Override() @SuppressWarnings(value = {"fallthrough", }) public Node optimizeSubtree(Node node) {
    switch (node.getType()){
      case Token.RETURN:
      {
        Node result = tryRemoveRedundantExit(node);
        if(result != node) {
          return result;
        }
        result = tryReplaceExitWithBreak(node);
        if(result != node) {
          return result;
        }
        return tryReduceReturn(node);
      }
      case Token.THROW:
      {
        Node result = tryRemoveRedundantExit(node);
        if(result != node) {
          return result;
        }
        return tryReplaceExitWithBreak(node);
      }
      case Token.NOT:
      tryMinimizeCondition(node.getFirstChild());
      return tryMinimizeNot(node);
      case Token.IF:
      Node var_1411 = node.getFirstChild();
      tryMinimizeCondition(var_1411);
      return tryMinimizeIf(node);
      case Token.EXPR_RESULT:
      tryMinimizeCondition(node.getFirstChild());
      return node;
      case Token.HOOK:
      tryMinimizeCondition(node.getFirstChild());
      return node;
      case Token.WHILE:
      case Token.DO:
      tryMinimizeCondition(NodeUtil.getConditionExpression(node));
      return node;
      case Token.FOR:
      if(!NodeUtil.isForIn(node)) {
        tryJoinForCondition(node);
        tryMinimizeCondition(NodeUtil.getConditionExpression(node));
      }
      return node;
      case Token.TRUE:
      case Token.FALSE:
      return reduceTrueFalse(node);
      case Token.NEW:
      node = tryFoldStandardConstructors(node);
      if(!node.isCall()) {
        return node;
      }
      case Token.CALL:
      Node result = tryFoldLiteralConstructor(node);
      if(result == node) {
        result = tryFoldSimpleFunctionCall(node);
        if(result == node) {
          result = tryFoldImmediateCallToBoundFunction(node);
        }
      }
      return result;
      case Token.COMMA:
      return trySplitComma(node);
      case Token.NAME:
      return tryReplaceUndefined(node);
      case Token.BLOCK:
      return tryReplaceIf(node);
      case Token.ARRAYLIT:
      return tryMinimizeArrayLiteral(node);
      default:
      return node;
    }
  }
  private Node reduceTrueFalse(Node n) {
    if(late) {
      Node not = IR.not(IR.number(n.isTrue() ? 0 : 1));
      not.copyInformationFromForTree(n);
      n.getParent().replaceChild(n, not);
      reportCodeChange();
      return not;
    }
    return n;
  }
  Node skipFinallyNodes(Node n) {
    while(n != null && NodeUtil.isTryFinallyNode(n.getParent(), n)){
      n = ControlFlowAnalysis.computeFollowNode(n);
    }
    return n;
  }
  private Node tryFoldImmediateCallToBoundFunction(Node n) {
    Preconditions.checkState(n.isCall());
    Node callTarget = n.getFirstChild();
    Bind bind = getCodingConvention().describeFunctionBind(callTarget, false);
    if(bind != null) {
      bind.target.detachFromParent();
      n.replaceChild(callTarget, bind.target);
      callTarget = bind.target;
      addParameterAfter(bind.parameters, callTarget);
      if(bind.thisValue != null && !NodeUtil.isUndefined(bind.thisValue)) {
        Node newCallTarget = IR.getprop(callTarget.cloneTree(), IR.string("call").srcref(callTarget));
        n.replaceChild(callTarget, newCallTarget);
        n.addChildAfter(bind.thisValue.cloneTree(), newCallTarget);
        n.putBooleanProp(Node.FREE_CALL, false);
      }
      else {
        n.putBooleanProp(Node.FREE_CALL, true);
      }
      reportCodeChange();
    }
    return n;
  }
  private Node tryFoldLiteralConstructor(Node n) {
    Preconditions.checkArgument(n.isCall() || n.isNew());
    Node constructorNameNode = n.getFirstChild();
    Node newLiteralNode = null;
    if(isASTNormalized() && Token.NAME == constructorNameNode.getType()) {
      String className = constructorNameNode.getString();
      if("RegExp".equals(className)) {
        return tryFoldRegularExpressionConstructor(n);
      }
      else {
        boolean constructorHasArgs = constructorNameNode.getNext() != null;
        if("Object".equals(className) && !constructorHasArgs) {
          newLiteralNode = IR.objectlit();
        }
        else 
          if("Array".equals(className)) {
            Node arg0 = constructorNameNode.getNext();
            FoldArrayAction action = isSafeToFoldArrayConstructor(arg0);
            if(action == FoldArrayAction.SAFE_TO_FOLD_WITH_ARGS || action == FoldArrayAction.SAFE_TO_FOLD_WITHOUT_ARGS) {
              newLiteralNode = IR.arraylit();
              n.removeChildren();
              if(action == FoldArrayAction.SAFE_TO_FOLD_WITH_ARGS) {
                newLiteralNode.addChildrenToFront(arg0);
              }
            }
          }
        if(newLiteralNode != null) {
          n.getParent().replaceChild(n, newLiteralNode);
          reportCodeChange();
          return newLiteralNode;
        }
      }
    }
    return n;
  }
  private Node tryFoldRegularExpressionConstructor(Node n) {
    Node parent = n.getParent();
    Node constructor = n.getFirstChild();
    Node pattern = constructor.getNext();
    Node flags = null != pattern ? pattern.getNext() : null;
    if(null == pattern || (null != flags && null != flags.getNext())) {
      return n;
    }
    if(pattern.isString() && !"".equals(pattern.getString()) && pattern.getString().length() < 100 && (null == flags || flags.isString()) && (isEcmaScript5OrGreater() || !containsUnicodeEscape(pattern.getString()))) {
      pattern = makeForwardSlashBracketSafe(pattern);
      Node regexLiteral;
      if(null == flags || "".equals(flags.getString())) {
        regexLiteral = IR.regexp(pattern);
      }
      else {
        if(!areValidRegexpFlags(flags.getString())) {
          report(INVALID_REGULAR_EXPRESSION_FLAGS, flags);
          return n;
        }
        if(!areSafeFlagsToFold(flags.getString())) {
          return n;
        }
        n.removeChild(flags);
        regexLiteral = IR.regexp(pattern, flags);
      }
      parent.replaceChild(n, regexLiteral);
      reportCodeChange();
      return regexLiteral;
    }
    return n;
  }
  private Node tryFoldSimpleFunctionCall(Node n) {
    Preconditions.checkState(n.isCall());
    Node callTarget = n.getFirstChild();
    if(callTarget != null && callTarget.isName() && callTarget.getString().equals("String")) {
      Node value = callTarget.getNext();
      if(value != null && value.getNext() == null && NodeUtil.isImmutableValue(value)) {
        Node addition = IR.add(IR.string("").srcref(callTarget), value.detachFromParent());
        n.getParent().replaceChild(n, addition);
        reportCodeChange();
        return addition;
      }
    }
    return n;
  }
  private Node tryFoldStandardConstructors(Node n) {
    Preconditions.checkState(n.isNew());
    if(isASTNormalized()) {
      if(n.getFirstChild().isName()) {
        String className = n.getFirstChild().getString();
        if(STANDARD_OBJECT_CONSTRUCTORS.contains(className)) {
          n.setType(Token.CALL);
          n.putBooleanProp(Node.FREE_CALL, true);
          reportCodeChange();
        }
      }
    }
    return n;
  }
  private Node tryMinimizeArrayLiteral(Node n) {
    boolean allStrings = true;
    for(com.google.javascript.rhino.Node cur = n.getFirstChild(); cur != null; cur = cur.getNext()) {
      if(!cur.isString()) {
        allStrings = false;
      }
    }
    if(allStrings) {
      return tryMinimizeStringArrayLiteral(n);
    }
    else {
      return n;
    }
  }
  private Node tryMinimizeCondition(Node n) {
    Node parent = n.getParent();
    switch (n.getType()){
      case Token.NOT:
      Node first = n.getFirstChild();
      switch (first.getType()){
        case Token.NOT:
        {
          Node newRoot = first.removeFirstChild();
          parent.replaceChild(n, newRoot);
          reportCodeChange();
          return newRoot;
        }
        case Token.AND:
        case Token.OR:
        {
          Node leftParent = first.getFirstChild();
          Node rightParent = first.getLastChild();
          Node left;
          Node right;
          if(!leftParent.isNot() && !rightParent.isNot()) {
            int op_precedence = NodeUtil.precedence(first.getType());
            if((isLowerPrecedence(leftParent, NOT_PRECEDENCE) && isHigherPrecedence(leftParent, op_precedence)) || (isLowerPrecedence(rightParent, NOT_PRECEDENCE) && isHigherPrecedence(rightParent, op_precedence))) {
              return n;
            }
          }
          if(leftParent.isNot()) {
            left = leftParent.removeFirstChild();
          }
          else {
            leftParent.detachFromParent();
            left = IR.not(leftParent).srcref(leftParent);
          }
          if(rightParent.isNot()) {
            right = rightParent.removeFirstChild();
          }
          else {
            rightParent.detachFromParent();
            right = IR.not(rightParent).srcref(rightParent);
          }
          int newOp = (first.isAnd()) ? Token.OR : Token.AND;
          Node newRoot = new Node(newOp, left, right);
          parent.replaceChild(n, newRoot);
          reportCodeChange();
          return newRoot;
        }
        default:
        TernaryValue nVal = NodeUtil.getPureBooleanValue(first);
        if(nVal != TernaryValue.UNKNOWN) {
          boolean result = nVal.not().toBoolean(true);
          int equivalentResult = result ? 1 : 0;
          return maybeReplaceChildWithNumber(n, parent, equivalentResult);
        }
      }
      return n;
      case Token.OR:
      case Token.AND:
      {
        Node left = n.getFirstChild();
        Node right = n.getLastChild();
        left = tryMinimizeCondition(left);
        right = tryMinimizeCondition(right);
        TernaryValue rightVal = NodeUtil.getPureBooleanValue(right);
        if(NodeUtil.getPureBooleanValue(right) != TernaryValue.UNKNOWN) {
          int type = n.getType();
          Node replacement = null;
          boolean rval = rightVal.toBoolean(true);
          if(type == Token.OR && !rval || type == Token.AND && rval) {
            replacement = left;
          }
          else 
            if(!mayHaveSideEffects(left)) {
              replacement = right;
            }
          if(replacement != null) {
            n.detachChildren();
            parent.replaceChild(n, replacement);
            reportCodeChange();
            return replacement;
          }
        }
        return n;
      }
      case Token.HOOK:
      {
        Node condition = n.getFirstChild();
        Node trueNode = n.getFirstChild().getNext();
        Node falseNode = n.getLastChild();
        trueNode = tryMinimizeCondition(trueNode);
        falseNode = tryMinimizeCondition(falseNode);
        Node replacement = null;
        TernaryValue trueNodeVal = NodeUtil.getPureBooleanValue(trueNode);
        TernaryValue falseNodeVal = NodeUtil.getPureBooleanValue(falseNode);
        if(trueNodeVal == TernaryValue.TRUE && falseNodeVal == TernaryValue.FALSE) {
          condition.detachFromParent();
          replacement = condition;
        }
        else 
          if(trueNodeVal == TernaryValue.FALSE && falseNodeVal == TernaryValue.TRUE) {
            condition.detachFromParent();
            replacement = IR.not(condition);
          }
          else 
            if(trueNodeVal == TernaryValue.TRUE) {
              n.detachChildren();
              replacement = IR.or(condition, falseNode);
            }
            else 
              if(falseNodeVal == TernaryValue.FALSE) {
                n.detachChildren();
                replacement = IR.and(condition, trueNode);
              }
        if(replacement != null) {
          parent.replaceChild(n, replacement);
          n = replacement;
          reportCodeChange();
        }
        return n;
      }
      default:
      TernaryValue nVal = NodeUtil.getPureBooleanValue(n);
      if(nVal != TernaryValue.UNKNOWN) {
        boolean result = nVal.toBoolean(true);
        int equivalentResult = result ? 1 : 0;
        return maybeReplaceChildWithNumber(n, parent, equivalentResult);
      }
      return n;
    }
  }
  private Node tryMinimizeIf(Node n) {
    Node parent = n.getParent();
    Node cond = n.getFirstChild();
    if(NodeUtil.isLiteralValue(cond, true)) {
      return n;
    }
    Node thenBranch = cond.getNext();
    Node elseBranch = thenBranch.getNext();
    if(elseBranch == null) {
      if(isFoldableExpressBlock(thenBranch)) {
        Node expr = getBlockExpression(thenBranch);
        if(!late && isPropertyAssignmentInExpression(expr)) {
          return n;
        }
        if(cond.isNot()) {
          if(isLowerPrecedenceInExpression(cond, OR_PRECEDENCE) && isLowerPrecedenceInExpression(expr.getFirstChild(), OR_PRECEDENCE)) {
            return n;
          }
          Node or = IR.or(cond.removeFirstChild(), expr.removeFirstChild()).srcref(n);
          Node newExpr = NodeUtil.newExpr(or);
          parent.replaceChild(n, newExpr);
          reportCodeChange();
          return newExpr;
        }
        if(isLowerPrecedenceInExpression(cond, AND_PRECEDENCE) && isLowerPrecedenceInExpression(expr.getFirstChild(), AND_PRECEDENCE)) {
          return n;
        }
        n.removeChild(cond);
        Node and = IR.and(cond, expr.removeFirstChild()).srcref(n);
        Node newExpr = NodeUtil.newExpr(and);
        parent.replaceChild(n, newExpr);
        reportCodeChange();
        return newExpr;
      }
      else {
        if(NodeUtil.isStatementBlock(thenBranch) && thenBranch.hasOneChild()) {
          Node innerIf = thenBranch.getFirstChild();
          if(innerIf.isIf()) {
            Node innerCond = innerIf.getFirstChild();
            Node innerThenBranch = innerCond.getNext();
            Node innerElseBranch = innerThenBranch.getNext();
            if(innerElseBranch == null && !(isLowerPrecedenceInExpression(cond, AND_PRECEDENCE) && isLowerPrecedenceInExpression(innerCond, AND_PRECEDENCE))) {
              n.detachChildren();
              n.addChildToBack(IR.and(cond, innerCond.detachFromParent()).srcref(cond));
              n.addChildrenToBack(innerThenBranch.detachFromParent());
              reportCodeChange();
              return n;
            }
          }
        }
      }
      return n;
    }
    tryRemoveRepeatedStatements(n);
    if(cond.isNot() && !consumesDanglingElse(elseBranch)) {
      n.replaceChild(cond, cond.removeFirstChild());
      n.removeChild(thenBranch);
      n.addChildToBack(thenBranch);
      reportCodeChange();
      return n;
    }
    if(isReturnExpressBlock(thenBranch) && isReturnExpressBlock(elseBranch)) {
      Node thenExpr = getBlockReturnExpression(thenBranch);
      Node elseExpr = getBlockReturnExpression(elseBranch);
      n.removeChild(cond);
      thenExpr.detachFromParent();
      elseExpr.detachFromParent();
      Node returnNode = IR.returnNode(IR.hook(cond, thenExpr, elseExpr).srcref(n));
      parent.replaceChild(n, returnNode);
      reportCodeChange();
      return returnNode;
    }
    boolean thenBranchIsExpressionBlock = isFoldableExpressBlock(thenBranch);
    boolean elseBranchIsExpressionBlock = isFoldableExpressBlock(elseBranch);
    if(thenBranchIsExpressionBlock && elseBranchIsExpressionBlock) {
      Node thenOp = getBlockExpression(thenBranch).getFirstChild();
      Node elseOp = getBlockExpression(elseBranch).getFirstChild();
      if(thenOp.getType() == elseOp.getType()) {
        if(NodeUtil.isAssignmentOp(thenOp)) {
          Node lhs = thenOp.getFirstChild();
          if(areNodesEqualForInlining(lhs, elseOp.getFirstChild()) && !mayEffectMutableState(lhs)) {
            n.removeChild(cond);
            Node assignName = thenOp.removeFirstChild();
            Node thenExpr = thenOp.removeFirstChild();
            Node elseExpr = elseOp.getLastChild();
            elseOp.removeChild(elseExpr);
            Node hookNode = IR.hook(cond, thenExpr, elseExpr).srcref(n);
            Node assign = new Node(thenOp.getType(), assignName, hookNode).srcref(thenOp);
            Node expr = NodeUtil.newExpr(assign);
            parent.replaceChild(n, expr);
            reportCodeChange();
            return expr;
          }
        }
      }
      n.removeChild(cond);
      thenOp.detachFromParent();
      elseOp.detachFromParent();
      Node expr = IR.exprResult(IR.hook(cond, thenOp, elseOp).srcref(n));
      parent.replaceChild(n, expr);
      reportCodeChange();
      return expr;
    }
    boolean thenBranchIsVar = isVarBlock(thenBranch);
    boolean elseBranchIsVar = isVarBlock(elseBranch);
    if(thenBranchIsVar && elseBranchIsExpressionBlock && getBlockExpression(elseBranch).getFirstChild().isAssign()) {
      Node var = getBlockVar(thenBranch);
      Node elseAssign = getBlockExpression(elseBranch).getFirstChild();
      Node name1 = var.getFirstChild();
      Node maybeName2 = elseAssign.getFirstChild();
      if(name1.hasChildren() && maybeName2.isName() && name1.getString().equals(maybeName2.getString())) {
        Node thenExpr = name1.removeChildren();
        Node elseExpr = elseAssign.getLastChild().detachFromParent();
        cond.detachFromParent();
        Node hookNode = IR.hook(cond, thenExpr, elseExpr).srcref(n);
        var.detachFromParent();
        name1.addChildrenToBack(hookNode);
        parent.replaceChild(n, var);
        reportCodeChange();
        return var;
      }
    }
    else 
      if(elseBranchIsVar && thenBranchIsExpressionBlock && getBlockExpression(thenBranch).getFirstChild().isAssign()) {
        Node var = getBlockVar(elseBranch);
        Node thenAssign = getBlockExpression(thenBranch).getFirstChild();
        Node maybeName1 = thenAssign.getFirstChild();
        Node name2 = var.getFirstChild();
        if(name2.hasChildren() && maybeName1.isName() && maybeName1.getString().equals(name2.getString())) {
          Node thenExpr = thenAssign.getLastChild().detachFromParent();
          Node elseExpr = name2.removeChildren();
          cond.detachFromParent();
          Node hookNode = IR.hook(cond, thenExpr, elseExpr).srcref(n);
          var.detachFromParent();
          name2.addChildrenToBack(hookNode);
          parent.replaceChild(n, var);
          reportCodeChange();
          return var;
        }
      }
    return n;
  }
  private Node tryMinimizeNot(Node n) {
    Node parent = n.getParent();
    Node notChild = n.getFirstChild();
    int complementOperator;
    switch (notChild.getType()){
      case Token.EQ:
      complementOperator = Token.NE;
      break ;
      case Token.NE:
      complementOperator = Token.EQ;
      break ;
      case Token.SHEQ:
      complementOperator = Token.SHNE;
      break ;
      case Token.SHNE:
      complementOperator = Token.SHEQ;
      break ;
      default:
      return n;
    }
    Node newOperator = n.removeFirstChild();
    newOperator.setType(complementOperator);
    parent.replaceChild(n, newOperator);
    reportCodeChange();
    return newOperator;
  }
  private Node tryMinimizeStringArrayLiteral(Node n) {
    if(!late) {
      return n;
    }
    int numElements = n.getChildCount();
    int saving = numElements * 2 - STRING_SPLIT_OVERHEAD;
    if(saving <= 0) {
      return n;
    }
    String[] strings = new String[n.getChildCount()];
    int idx = 0;
    for(com.google.javascript.rhino.Node cur = n.getFirstChild(); cur != null; cur = cur.getNext()) {
      strings[idx++] = cur.getString();
    }
    String delimiter = pickDelimiter(strings);
    if(delimiter != null) {
      String template = Joiner.on(delimiter).join(strings);
      Node call = IR.call(IR.getprop(IR.string(template), IR.string("split")), IR.string("" + delimiter));
      call.copyInformationFromForTree(n);
      n.getParent().replaceChild(n, call);
      reportCodeChange();
      return call;
    }
    return n;
  }
  private Node tryReduceReturn(Node n) {
    Node result = n.getFirstChild();
    if(result != null) {
      switch (result.getType()){
        case Token.VOID:
        Node operand = result.getFirstChild();
        if(!mayHaveSideEffects(operand)) {
          n.removeFirstChild();
          reportCodeChange();
        }
        break ;
        case Token.NAME:
        String name = result.getString();
        if(name.equals("undefined")) {
          n.removeFirstChild();
          reportCodeChange();
        }
        break ;
      }
    }
    return n;
  }
  private Node tryRemoveRedundantExit(Node n) {
    Node exitExpr = n.getFirstChild();
    Node follow = ControlFlowAnalysis.computeFollowNode(n);
    Node prefinallyFollows = follow;
    follow = skipFinallyNodes(follow);
    if(prefinallyFollows != follow) {
      if(!isPure(exitExpr)) {
        return n;
      }
    }
    if(follow == null && (n.isThrow() || exitExpr != null)) {
      return n;
    }
    if(follow == null || areMatchingExits(n, follow)) {
      n.detachFromParent();
      reportCodeChange();
      return null;
    }
    return n;
  }
  private Node tryReplaceExitWithBreak(Node n) {
    Node result = n.getFirstChild();
    Node breakTarget = n;
    for(; !ControlFlowAnalysis.isBreakTarget(breakTarget, null); breakTarget = breakTarget.getParent()) {
      if(breakTarget.isFunction() || breakTarget.isScript()) {
        return n;
      }
    }
    Node follow = ControlFlowAnalysis.computeFollowNode(breakTarget);
    Node prefinallyFollows = follow;
    follow = skipFinallyNodes(follow);
    if(prefinallyFollows != follow) {
      if(!isPure(result)) {
        return n;
      }
    }
    if(follow == null && (n.isThrow() || result != null)) {
      return n;
    }
    if(follow == null || areMatchingExits(n, follow)) {
      Node replacement = IR.breakNode();
      n.getParent().replaceChild(n, replacement);
      this.reportCodeChange();
      return replacement;
    }
    return n;
  }
  private Node tryReplaceIf(Node n) {
    for(com.google.javascript.rhino.Node child = n.getFirstChild(); child != null; child = child.getNext()) {
      if(child.isIf()) {
        Node cond = child.getFirstChild();
        Node thenBranch = cond.getNext();
        Node elseBranch = thenBranch.getNext();
        Node nextNode = child.getNext();
        if(nextNode != null && elseBranch == null && isReturnBlock(thenBranch) && nextNode.isIf()) {
          Node nextCond = nextNode.getFirstChild();
          Node nextThen = nextCond.getNext();
          Node nextElse = nextThen.getNext();
          if(thenBranch.isEquivalentToTyped(nextThen)) {
            child.detachFromParent();
            child.detachChildren();
            Node newCond = new Node(Token.OR, cond);
            nextNode.replaceChild(nextCond, newCond);
            newCond.addChildToBack(nextCond);
            reportCodeChange();
          }
          else 
            if(nextElse != null && thenBranch.isEquivalentToTyped(nextElse)) {
              child.detachFromParent();
              child.detachChildren();
              Node newCond = new Node(Token.AND, IR.not(cond).srcref(cond));
              nextNode.replaceChild(nextCond, newCond);
              newCond.addChildToBack(nextCond);
              reportCodeChange();
            }
        }
        else 
          if(nextNode != null && elseBranch == null && isReturnBlock(thenBranch) && isReturnExpression(nextNode)) {
            Node thenExpr = null;
            if(isReturnExpressBlock(thenBranch)) {
              thenExpr = getBlockReturnExpression(thenBranch);
              thenExpr.detachFromParent();
            }
            else {
              thenExpr = NodeUtil.newUndefinedNode(child);
            }
            Node elseExpr = nextNode.getFirstChild();
            cond.detachFromParent();
            elseExpr.detachFromParent();
            Node returnNode = IR.returnNode(IR.hook(cond, thenExpr, elseExpr).srcref(child));
            n.replaceChild(child, returnNode);
            n.removeChild(nextNode);
            reportCodeChange();
          }
          else 
            if(elseBranch != null && statementMustExitParent(thenBranch)) {
              child.removeChild(elseBranch);
              n.addChildAfter(elseBranch, child);
              reportCodeChange();
            }
      }
    }
    return n;
  }
  private Node tryReplaceUndefined(Node n) {
    if(isASTNormalized() && NodeUtil.isUndefined(n) && !NodeUtil.isLValue(n)) {
      Node replacement = NodeUtil.newUndefinedNode(n);
      n.getParent().replaceChild(n, replacement);
      reportCodeChange();
      return replacement;
    }
    return n;
  }
  private Node trySplitComma(Node n) {
    if(late) {
      return n;
    }
    Node parent = n.getParent();
    Node left = n.getFirstChild();
    Node right = n.getLastChild();
    if(parent.isExprResult() && !parent.getParent().isLabel()) {
      n.detachChildren();
      parent.replaceChild(n, left);
      Node newStatement = IR.exprResult(right);
      newStatement.copyInformationFrom(n);
      parent.getParent().addChildAfter(newStatement, parent);
      reportCodeChange();
      return left;
    }
    else {
      return n;
    }
  }
  private String pickDelimiter(String[] strings) {
    boolean allLength1 = true;
    for (String s : strings) {
      if(s.length() != 1) {
        allLength1 = false;
        break ;
      }
    }
    if(allLength1) {
      return "";
    }
    String[] delimiters = new String[]{ " ", ";", ",", "{", "}", null } ;
    int i = 0;
    NEXT_DELIMITER:
      for(; delimiters[i] != null; i++) {
        for (String cur : strings) {
          if(cur.contains(delimiters[i])) {
            continue NEXT_DELIMITER;
          }
        }
        break ;
      }
    return delimiters[i];
  }
  boolean areMatchingExits(Node nodeThis, Node nodeThat) {
    return nodeThis.isEquivalentTo(nodeThat) && (!isExceptionPossible(nodeThis) || getExceptionHandler(nodeThis) == getExceptionHandler(nodeThat));
  }
  private boolean areSafeFlagsToFold(String flags) {
    return isEcmaScript5OrGreater() || flags.indexOf('g') < 0;
  }
  private static boolean areValidRegexpFlags(String flags) {
    return REGEXP_FLAGS_RE.matcher(flags).matches();
  }
  private boolean consumesDanglingElse(Node n) {
    while(true){
      switch (n.getType()){
        case Token.IF:
        if(n.getChildCount() < 3) {
          return true;
        }
        n = n.getLastChild();
        continue ;
        case Token.WITH:
        case Token.WHILE:
        case Token.FOR:
        n = n.getLastChild();
        continue ;
        default:
        return false;
      }
    }
  }
  static boolean containsUnicodeEscape(String s) {
    String esc = REGEXP_ESCAPER.regexpEscape(s);
    for(int i = -1; (i = esc.indexOf("\\u", i + 1)) >= 0; ) {
      int nSlashes = 0;
      while(i - nSlashes > 0 && '\\' == esc.charAt(i - nSlashes - 1)){
        ++nSlashes;
      }
      if(0 == (nSlashes & 1)) {
        return true;
      }
    }
    return false;
  }
  boolean isExceptionPossible(Node n) {
    Preconditions.checkState(n.isReturn() || n.isThrow());
    return n.isThrow() || (n.hasChildren() && !NodeUtil.isLiteralValue(n.getLastChild(), true));
  }
  private boolean isFoldableExpressBlock(Node n) {
    if(n.isBlock()) {
      if(n.hasOneChild()) {
        Node maybeExpr = n.getFirstChild();
        if(maybeExpr.isExprResult()) {
          if(maybeExpr.getFirstChild().isCall()) {
            Node calledFn = maybeExpr.getFirstChild().getFirstChild();
            if(calledFn.isGetElem()) {
              return false;
            }
            else 
              if(calledFn.isGetProp() && calledFn.getLastChild().getString().startsWith("on")) {
                return false;
              }
          }
          return true;
        }
        return false;
      }
    }
    return false;
  }
  private boolean isHigherPrecedence(Node n, final int precedence) {
    return NodeUtil.precedence(n.getType()) > precedence;
  }
  private boolean isLowerPrecedence(Node n, final int precedence) {
    return NodeUtil.precedence(n.getType()) < precedence;
  }
  private boolean isLowerPrecedenceInExpression(Node n, final int precedence) {
    Predicate<Node> isLowerPrecedencePredicate = new Predicate<Node>() {
        @Override() public boolean apply(Node input) {
          return NodeUtil.precedence(input.getType()) < precedence;
        }
    };
    return NodeUtil.has(n, isLowerPrecedencePredicate, DONT_TRAVERSE_FUNCTIONS_PREDICATE);
  }
  private boolean isPropertyAssignmentInExpression(Node n) {
    Predicate<Node> isPropertyAssignmentInExpressionPredicate = new Predicate<Node>() {
        @Override() public boolean apply(Node input) {
          return (input.isGetProp() && input.getParent().isAssign());
        }
    };
    return NodeUtil.has(n, isPropertyAssignmentInExpressionPredicate, DONT_TRAVERSE_FUNCTIONS_PREDICATE);
  }
  boolean isPure(Node n) {
    return n == null || (!NodeUtil.canBeSideEffected(n) && !mayHaveSideEffects(n));
  }
  private boolean isReturnBlock(Node n) {
    if(n.isBlock()) {
      if(n.hasOneChild()) {
        Node first = n.getFirstChild();
        return first.isReturn();
      }
    }
    return false;
  }
  private boolean isReturnExpressBlock(Node n) {
    if(n.isBlock()) {
      if(n.hasOneChild()) {
        Node first = n.getFirstChild();
        if(first.isReturn()) {
          return first.hasOneChild();
        }
      }
    }
    return false;
  }
  private boolean isReturnExpression(Node n) {
    if(n.isReturn()) {
      return n.hasOneChild();
    }
    return false;
  }
  private boolean isVarBlock(Node n) {
    if(n.isBlock()) {
      if(n.hasOneChild()) {
        Node first = n.getFirstChild();
        if(first.isVar()) {
          return first.hasOneChild();
        }
      }
    }
    return false;
  }
  private boolean statementMustExitParent(Node n) {
    switch (n.getType()){
      case Token.THROW:
      case Token.RETURN:
      return true;
      case Token.BLOCK:
      if(n.hasChildren()) {
        Node child = n.getLastChild();
        return statementMustExitParent(child);
      }
      return false;
      case Token.FUNCTION:
      default:
      return false;
    }
  }
  private void addParameterAfter(Node parameterList, Node after) {
    if(parameterList != null) {
      addParameterAfter(parameterList.getNext(), after);
      after.getParent().addChildAfter(parameterList.cloneTree(), after);
    }
  }
  private void tryJoinForCondition(Node n) {
    if(!late) {
      return ;
    }
    Node block = n.getLastChild();
    Node maybeIf = block.getFirstChild();
    if(maybeIf != null && maybeIf.isIf()) {
      Node maybeBreak = maybeIf.getChildAtIndex(1).getFirstChild();
      if(maybeBreak != null && maybeBreak.isBreak() && !maybeBreak.hasChildren()) {
        if(maybeIf.getChildCount() == 3) {
          block.replaceChild(maybeIf, maybeIf.getLastChild().detachFromParent());
        }
        else {
          block.removeFirstChild();
        }
        Node ifCondition = maybeIf.removeFirstChild();
        Node fixedIfCondition = IR.not(ifCondition).srcref(ifCondition);
        Node forCondition = NodeUtil.getConditionExpression(n);
        if(forCondition.isEmpty()) {
          n.replaceChild(forCondition, fixedIfCondition);
        }
        else {
          Node replacement = new Node(Token.AND);
          n.replaceChild(forCondition, replacement);
          replacement.addChildToBack(forCondition);
          replacement.addChildToBack(fixedIfCondition);
        }
        reportCodeChange();
      }
    }
  }
  private void tryRemoveRepeatedStatements(Node n) {
    Preconditions.checkState(n.isIf());
    Node parent = n.getParent();
    if(!NodeUtil.isStatementBlock(parent)) {
      return ;
    }
    Node cond = n.getFirstChild();
    Node trueBranch = cond.getNext();
    Node falseBranch = trueBranch.getNext();
    Preconditions.checkNotNull(trueBranch);
    Preconditions.checkNotNull(falseBranch);
    while(true){
      Node lastTrue = trueBranch.getLastChild();
      Node lastFalse = falseBranch.getLastChild();
      if(lastTrue == null || lastFalse == null || !areNodesEqualForInlining(lastTrue, lastFalse)) {
        break ;
      }
      lastTrue.detachFromParent();
      lastFalse.detachFromParent();
      parent.addChildAfter(lastTrue, n);
      reportCodeChange();
    }
  }
  private static enum FoldArrayAction {
    NOT_SAFE_TO_FOLD(),

    SAFE_TO_FOLD_WITH_ARGS(),

    SAFE_TO_FOLD_WITHOUT_ARGS(),

  ;
  private FoldArrayAction() {
  }
  }
}