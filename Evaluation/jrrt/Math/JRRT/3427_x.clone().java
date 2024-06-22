package org.apache.commons.math3.optimization.direct;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.MathArrays;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.exception.NumberIsTooSmallException;
import org.apache.commons.math3.exception.NotStrictlyPositiveException;
import org.apache.commons.math3.optimization.GoalType;
import org.apache.commons.math3.optimization.PointValuePair;
import org.apache.commons.math3.optimization.ConvergenceChecker;
import org.apache.commons.math3.optimization.MultivariateOptimizer;
import org.apache.commons.math3.optimization.univariate.BracketFinder;
import org.apache.commons.math3.optimization.univariate.BrentOptimizer;
import org.apache.commons.math3.optimization.univariate.UnivariatePointValuePair;
import org.apache.commons.math3.optimization.univariate.SimpleUnivariateValueChecker;

@Deprecated() public class PowellOptimizer extends BaseAbstractMultivariateOptimizer<MultivariateFunction> implements MultivariateOptimizer  {
  final private static double MIN_RELATIVE_TOLERANCE = 2 * FastMath.ulp(1D);
  final private double relativeThreshold;
  final private double absoluteThreshold;
  final private LineSearch line;
  public PowellOptimizer(double rel, double abs) {
    this(rel, abs, null);
  }
  public PowellOptimizer(double rel, double abs, ConvergenceChecker<PointValuePair> checker) {
    this(rel, abs, FastMath.sqrt(rel), FastMath.sqrt(abs), checker);
  }
  public PowellOptimizer(double rel, double abs, double lineRel, double lineAbs) {
    this(rel, abs, lineRel, lineAbs, null);
  }
  public PowellOptimizer(double rel, double abs, double lineRel, double lineAbs, ConvergenceChecker<PointValuePair> checker) {
    super(checker);
    if(rel < MIN_RELATIVE_TOLERANCE) {
      throw new NumberIsTooSmallException(rel, MIN_RELATIVE_TOLERANCE, true);
    }
    if(abs <= 0) {
      throw new NotStrictlyPositiveException(abs);
    }
    relativeThreshold = rel;
    absoluteThreshold = abs;
    line = new LineSearch(lineRel, lineAbs);
  }
  @Override() protected PointValuePair doOptimize() {
    final GoalType goal = getGoalType();
    final double[] guess = getStartPoint();
    final int n = guess.length;
    final double[][] direc = new double[n][n];
    for(int i = 0; i < n; i++) {
      direc[i][i] = 1;
    }
    final ConvergenceChecker<PointValuePair> checker = getConvergenceChecker();
    double[] x = guess;
    double fVal = computeObjectiveValue(x);
    double[] var_3427 = x.clone();
    double[] x1 = var_3427;
    int iter = 0;
    while(true){
      ++iter;
      double fX = fVal;
      double fX2 = 0;
      double delta = 0;
      int bigInd = 0;
      double alphaMin = 0;
      for(int i = 0; i < n; i++) {
        final double[] d = MathArrays.copyOf(direc[i]);
        fX2 = fVal;
        final UnivariatePointValuePair optimum = line.search(x, d);
        fVal = optimum.getValue();
        alphaMin = optimum.getPoint();
        final double[][] result = newPointAndDirection(x, d, alphaMin);
        x = result[0];
        if((fX2 - fVal) > delta) {
          delta = fX2 - fVal;
          bigInd = i;
        }
      }
      boolean stop = 2 * (fX - fVal) <= (relativeThreshold * (FastMath.abs(fX) + FastMath.abs(fVal)) + absoluteThreshold);
      final PointValuePair previous = new PointValuePair(x1, fX);
      final PointValuePair current = new PointValuePair(x, fVal);
      if(!stop && checker != null) {
        stop = checker.converged(iter, previous, current);
      }
      if(stop) {
        if(goal == GoalType.MINIMIZE) {
          return (fVal < fX) ? current : previous;
        }
        else {
          return (fVal > fX) ? current : previous;
        }
      }
      final double[] d = new double[n];
      final double[] x2 = new double[n];
      for(int i = 0; i < n; i++) {
        d[i] = x[i] - x1[i];
        x2[i] = 2 * x[i] - x1[i];
      }
      x1 = x.clone();
      fX2 = computeObjectiveValue(x2);
      if(fX > fX2) {
        double t = 2 * (fX + fX2 - 2 * fVal);
        double temp = fX - fVal - delta;
        t *= temp * temp;
        temp = fX - fX2;
        t -= delta * temp * temp;
        if(t < 0.0D) {
          final UnivariatePointValuePair optimum = line.search(x, d);
          fVal = optimum.getValue();
          alphaMin = optimum.getPoint();
          final double[][] result = newPointAndDirection(x, d, alphaMin);
          x = result[0];
          final int lastInd = n - 1;
          direc[bigInd] = direc[lastInd];
          direc[lastInd] = result[1];
        }
      }
    }
  }
  private double[][] newPointAndDirection(double[] p, double[] d, double optimum) {
    final int n = p.length;
    final double[] nP = new double[n];
    final double[] nD = new double[n];
    for(int i = 0; i < n; i++) {
      nD[i] = d[i] * optimum;
      nP[i] = p[i] + nD[i];
    }
    final double[][] result = new double[2][];
    result[0] = nP;
    result[1] = nD;
    return result;
  }
  
  private class LineSearch extends BrentOptimizer  {
    final private static double REL_TOL_UNUSED = 1e-15D;
    final private static double ABS_TOL_UNUSED = Double.MIN_VALUE;
    final private BracketFinder bracket = new BracketFinder();
    LineSearch(double rel, double abs) {
      super(REL_TOL_UNUSED, ABS_TOL_UNUSED, new SimpleUnivariateValueChecker(rel, abs));
    }
    public UnivariatePointValuePair search(final double[] p, final double[] d) {
      final int n = p.length;
      final UnivariateFunction f = new UnivariateFunction() {
          public double value(double alpha) {
            final double[] x = new double[n];
            for(int i = 0; i < n; i++) {
              x[i] = p[i] + alpha * d[i];
            }
            final double obj = PowellOptimizer.this.computeObjectiveValue(x);
            return obj;
          }
      };
      final GoalType goal = PowellOptimizer.this.getGoalType();
      bracket.search(f, goal, 0, 1);
      return optimize(Integer.MAX_VALUE, f, goal, bracket.getLo(), bracket.getHi(), bracket.getMid());
    }
  }
}