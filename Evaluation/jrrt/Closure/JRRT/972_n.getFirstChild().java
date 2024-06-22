package com.google.javascript.jscomp;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.ControlFlowGraph.Branch;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphEdge;
import com.google.javascript.jscomp.graph.LatticeElement;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

class LiveVariablesAnalysis extends DataFlowAnalysis<Node, LiveVariablesAnalysis.LiveVariableLattice>  {
  final static int MAX_VARIABLES_TO_ANALYZE = 100;
  final public static String ARGUMENT_ARRAY_ALIAS = "arguments";
  final private Scope jsScope;
  final private Set<Var> escaped;
  LiveVariablesAnalysis(ControlFlowGraph<Node> cfg, Scope jsScope, AbstractCompiler compiler) {
    super(cfg, new LiveVariableJoinOp());
    this.jsScope = jsScope;
    this.escaped = Sets.newHashSet();
    computeEscaped(jsScope, escaped, compiler);
  }
  @Override() LiveVariableLattice createEntryLattice() {
    return new LiveVariableLattice(jsScope.getVarCount());
  }
  @Override() LiveVariableLattice createInitialEstimateLattice() {
    return new LiveVariableLattice(jsScope.getVarCount());
  }
  @Override() LiveVariableLattice flowThrough(Node node, LiveVariableLattice input) {
    final BitSet gen = new BitSet(input.liveSet.size());
    final BitSet kill = new BitSet(input.liveSet.size());
    boolean conditional = false;
    List<DiGraphEdge<Node, Branch>> edgeList = getCfg().getOutEdges(node);
    for (DiGraphEdge<Node, Branch> edge : edgeList) {
      if(Branch.ON_EX.equals(edge.getValue())) {
        conditional = true;
      }
    }
    computeGenKill(node, gen, kill, conditional);
    LiveVariableLattice result = new LiveVariableLattice(input);
    result.liveSet.andNot(kill);
    result.liveSet.or(gen);
    return result;
  }
  public Set<Var> getEscapedLocals() {
    return escaped;
  }
  private boolean isArgumentsName(Node n) {
    if(!n.isName() || !n.getString().equals(ARGUMENT_ARRAY_ALIAS) || jsScope.isDeclared(ARGUMENT_ARRAY_ALIAS, false)) {
      return false;
    }
    else {
      return true;
    }
  }
  @Override() boolean isForward() {
    return false;
  }
  public int getVarIndex(String var) {
    return jsScope.getVar(var).index;
  }
  private void addToSetIfLocal(Node node, BitSet set) {
    Preconditions.checkState(node.isName());
    String name = node.getString();
    if(!jsScope.isDeclared(name, false)) {
      return ;
    }
    Var var = jsScope.getVar(name);
    if(!escaped.contains(var)) {
      set.set(var.index);
    }
  }
  private void computeGenKill(Node n, BitSet gen, BitSet kill, boolean conditional) {
    switch (n.getType()){
      case Token.SCRIPT:
      case Token.BLOCK:
      case Token.FUNCTION:
      return ;
      case Token.WHILE:
      case Token.DO:
      case Token.IF:
      computeGenKill(NodeUtil.getConditionExpression(n), gen, kill, conditional);
      return ;
      case Token.FOR:
      if(!NodeUtil.isForIn(n)) {
        computeGenKill(NodeUtil.getConditionExpression(n), gen, kill, conditional);
      }
      else {
        Node lhs = n.getFirstChild();
        Node rhs = lhs.getNext();
        if(lhs.isVar()) {
          lhs = lhs.getLastChild();
        }
        if(lhs.isName()) {
          addToSetIfLocal(lhs, kill);
          addToSetIfLocal(lhs, gen);
        }
        else {
          computeGenKill(lhs, gen, kill, conditional);
        }
      }
      return ;
      case Token.VAR:
      for(com.google.javascript.rhino.Node c = n.getFirstChild(); c != null; c = c.getNext()) {
        if(c.hasChildren()) {
          computeGenKill(c.getFirstChild(), gen, kill, conditional);
          if(!conditional) {
            addToSetIfLocal(c, kill);
          }
        }
      }
      return ;
      case Token.AND:
      case Token.OR:
      computeGenKill(n.getFirstChild(), gen, kill, conditional);
      computeGenKill(n.getLastChild(), gen, kill, true);
      return ;
      case Token.HOOK:
      Node var_972 = n.getFirstChild();
      computeGenKill(var_972, gen, kill, conditional);
      computeGenKill(n.getFirstChild().getNext(), gen, kill, true);
      computeGenKill(n.getLastChild(), gen, kill, true);
      return ;
      case Token.NAME:
      if(isArgumentsName(n)) {
        markAllParametersEscaped();
      }
      else {
        addToSetIfLocal(n, gen);
      }
      return ;
      default:
      if(NodeUtil.isAssignmentOp(n) && n.getFirstChild().isName()) {
        Node lhs = n.getFirstChild();
        if(!conditional) {
          addToSetIfLocal(lhs, kill);
        }
        if(!n.isAssign()) {
          addToSetIfLocal(lhs, gen);
        }
        computeGenKill(lhs.getNext(), gen, kill, conditional);
      }
      else {
        for(com.google.javascript.rhino.Node c = n.getFirstChild(); c != null; c = c.getNext()) {
          computeGenKill(c, gen, kill, conditional);
        }
      }
      return ;
    }
  }
  void markAllParametersEscaped() {
    Node lp = jsScope.getRootNode().getFirstChild().getNext();
    for(com.google.javascript.rhino.Node arg = lp.getFirstChild(); arg != null; arg = arg.getNext()) {
      escaped.add(jsScope.getVar(arg.getString()));
    }
  }
  
  private static class LiveVariableJoinOp implements JoinOp<LiveVariableLattice>  {
    @Override() public LiveVariableLattice apply(List<LiveVariableLattice> in) {
      LiveVariableLattice result = new LiveVariableLattice(in.get(0));
      for(int i = 1; i < in.size(); i++) {
        result.liveSet.or(in.get(i).liveSet);
      }
      return result;
    }
  }
  
  static class LiveVariableLattice implements LatticeElement  {
    final private BitSet liveSet;
    private LiveVariableLattice(LiveVariableLattice other) {
      super();
      Preconditions.checkNotNull(other);
      this.liveSet = (BitSet)other.liveSet.clone();
    }
    private LiveVariableLattice(int numVars) {
      super();
      this.liveSet = new BitSet(numVars);
    }
    @Override() public String toString() {
      return liveSet.toString();
    }
    @Override() public boolean equals(Object other) {
      Preconditions.checkNotNull(other);
      return (other instanceof LiveVariableLattice) && this.liveSet.equals(((LiveVariableLattice)other).liveSet);
    }
    public boolean isLive(Var v) {
      Preconditions.checkNotNull(v);
      return liveSet.get(v.index);
    }
    public boolean isLive(int index) {
      return liveSet.get(index);
    }
    @Override() public int hashCode() {
      return liveSet.hashCode();
    }
  }
}