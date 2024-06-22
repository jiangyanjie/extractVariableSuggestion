package org.apache.commons.math3.linear;
import java.io.Serializable;
import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.exception.MathIllegalStateException;
import org.apache.commons.math3.exception.NoDataException;
import org.apache.commons.math3.exception.NotStrictlyPositiveException;
import org.apache.commons.math3.exception.NullArgumentException;
import org.apache.commons.math3.exception.NumberIsTooSmallException;
import org.apache.commons.math3.exception.OutOfRangeException;
import org.apache.commons.math3.exception.util.LocalizedFormats;
import org.apache.commons.math3.util.MathUtils;

public class Array2DRowRealMatrix extends AbstractRealMatrix implements Serializable  {
  final private static long serialVersionUID = -1067294169172445528L;
  private double[][] data;
  public Array2DRowRealMatrix() {
    super();
  }
  public Array2DRowRealMatrix(final double[] v) {
    super();
    final int nRows = v.length;
    data = new double[nRows][1];
    for(int row = 0; row < nRows; row++) {
      data[row][0] = v[row];
    }
  }
  public Array2DRowRealMatrix(final double[][] d) throws DimensionMismatchException, NoDataException, NullArgumentException {
    super();
    copyIn(d);
  }
  public Array2DRowRealMatrix(final double[][] d, final boolean copyArray) throws DimensionMismatchException, NoDataException, NullArgumentException {
    super();
    if(copyArray) {
      copyIn(d);
    }
    else {
      if(d == null) {
        throw new NullArgumentException();
      }
      final int nRows = d.length;
      if(nRows == 0) {
        throw new NoDataException(LocalizedFormats.AT_LEAST_ONE_ROW);
      }
      final int nCols = d[0].length;
      if(nCols == 0) {
        throw new NoDataException(LocalizedFormats.AT_LEAST_ONE_COLUMN);
      }
      for(int r = 1; r < nRows; r++) {
        double[] var_2296 = d[r];
        if(var_2296.length != nCols) {
          throw new DimensionMismatchException(d[r].length, nCols);
        }
      }
      data = d;
    }
  }
  public Array2DRowRealMatrix(final int rowDimension, final int columnDimension) throws NotStrictlyPositiveException {
    super(rowDimension, columnDimension);
    data = new double[rowDimension][columnDimension];
  }
  public Array2DRowRealMatrix add(final Array2DRowRealMatrix m) throws MatrixDimensionMismatchException {
    MatrixUtils.checkAdditionCompatible(this, m);
    final int rowCount = getRowDimension();
    final int columnCount = getColumnDimension();
    final double[][] outData = new double[rowCount][columnCount];
    for(int row = 0; row < rowCount; row++) {
      final double[] dataRow = data[row];
      final double[] mRow = m.data[row];
      final double[] outDataRow = outData[row];
      for(int col = 0; col < columnCount; col++) {
        outDataRow[col] = dataRow[col] + mRow[col];
      }
    }
    return new Array2DRowRealMatrix(outData, false);
  }
  public Array2DRowRealMatrix multiply(final Array2DRowRealMatrix m) throws DimensionMismatchException {
    MatrixUtils.checkMultiplicationCompatible(this, m);
    final int nRows = this.getRowDimension();
    final int nCols = m.getColumnDimension();
    final int nSum = this.getColumnDimension();
    final double[][] outData = new double[nRows][nCols];
    final double[] mCol = new double[nSum];
    final double[][] mData = m.data;
    for(int col = 0; col < nCols; col++) {
      for(int mRow = 0; mRow < nSum; mRow++) {
        mCol[mRow] = mData[mRow][col];
      }
      for(int row = 0; row < nRows; row++) {
        final double[] dataRow = data[row];
        double sum = 0;
        for(int i = 0; i < nSum; i++) {
          sum += dataRow[i] * mCol[i];
        }
        outData[row][col] = sum;
      }
    }
    return new Array2DRowRealMatrix(outData, false);
  }
  public Array2DRowRealMatrix subtract(final Array2DRowRealMatrix m) throws MatrixDimensionMismatchException {
    MatrixUtils.checkSubtractionCompatible(this, m);
    final int rowCount = getRowDimension();
    final int columnCount = getColumnDimension();
    final double[][] outData = new double[rowCount][columnCount];
    for(int row = 0; row < rowCount; row++) {
      final double[] dataRow = data[row];
      final double[] mRow = m.data[row];
      final double[] outDataRow = outData[row];
      for(int col = 0; col < columnCount; col++) {
        outDataRow[col] = dataRow[col] - mRow[col];
      }
    }
    return new Array2DRowRealMatrix(outData, false);
  }
  @Override() public RealMatrix copy() {
    return new Array2DRowRealMatrix(copyOut(), false);
  }
  @Override() public RealMatrix createMatrix(final int rowDimension, final int columnDimension) throws NotStrictlyPositiveException {
    return new Array2DRowRealMatrix(rowDimension, columnDimension);
  }
  @Override() public double getEntry(final int row, final int column) throws OutOfRangeException {
    MatrixUtils.checkMatrixIndex(this, row, column);
    return data[row][column];
  }
  @Override() public double walkInColumnOrder(final RealMatrixChangingVisitor visitor) {
    final int rows = getRowDimension();
    final int columns = getColumnDimension();
    visitor.start(rows, columns, 0, rows - 1, 0, columns - 1);
    for(int j = 0; j < columns; ++j) {
      for(int i = 0; i < rows; ++i) {
        final double[] rowI = data[i];
        rowI[j] = visitor.visit(i, j, rowI[j]);
      }
    }
    return visitor.end();
  }
  @Override() public double walkInColumnOrder(final RealMatrixChangingVisitor visitor, final int startRow, final int endRow, final int startColumn, final int endColumn) throws OutOfRangeException, NumberIsTooSmallException {
    MatrixUtils.checkSubMatrixIndex(this, startRow, endRow, startColumn, endColumn);
    visitor.start(getRowDimension(), getColumnDimension(), startRow, endRow, startColumn, endColumn);
    for(int j = startColumn; j <= endColumn; ++j) {
      for(int i = startRow; i <= endRow; ++i) {
        final double[] rowI = data[i];
        rowI[j] = visitor.visit(i, j, rowI[j]);
      }
    }
    return visitor.end();
  }
  @Override() public double walkInColumnOrder(final RealMatrixPreservingVisitor visitor) {
    final int rows = getRowDimension();
    final int columns = getColumnDimension();
    visitor.start(rows, columns, 0, rows - 1, 0, columns - 1);
    for(int j = 0; j < columns; ++j) {
      for(int i = 0; i < rows; ++i) {
        visitor.visit(i, j, data[i][j]);
      }
    }
    return visitor.end();
  }
  @Override() public double walkInColumnOrder(final RealMatrixPreservingVisitor visitor, final int startRow, final int endRow, final int startColumn, final int endColumn) throws OutOfRangeException, NumberIsTooSmallException {
    MatrixUtils.checkSubMatrixIndex(this, startRow, endRow, startColumn, endColumn);
    visitor.start(getRowDimension(), getColumnDimension(), startRow, endRow, startColumn, endColumn);
    for(int j = startColumn; j <= endColumn; ++j) {
      for(int i = startRow; i <= endRow; ++i) {
        visitor.visit(i, j, data[i][j]);
      }
    }
    return visitor.end();
  }
  @Override() public double walkInRowOrder(final RealMatrixChangingVisitor visitor) {
    final int rows = getRowDimension();
    final int columns = getColumnDimension();
    visitor.start(rows, columns, 0, rows - 1, 0, columns - 1);
    for(int i = 0; i < rows; ++i) {
      final double[] rowI = data[i];
      for(int j = 0; j < columns; ++j) {
        rowI[j] = visitor.visit(i, j, rowI[j]);
      }
    }
    return visitor.end();
  }
  @Override() public double walkInRowOrder(final RealMatrixChangingVisitor visitor, final int startRow, final int endRow, final int startColumn, final int endColumn) throws OutOfRangeException, NumberIsTooSmallException {
    MatrixUtils.checkSubMatrixIndex(this, startRow, endRow, startColumn, endColumn);
    visitor.start(getRowDimension(), getColumnDimension(), startRow, endRow, startColumn, endColumn);
    for(int i = startRow; i <= endRow; ++i) {
      final double[] rowI = data[i];
      for(int j = startColumn; j <= endColumn; ++j) {
        rowI[j] = visitor.visit(i, j, rowI[j]);
      }
    }
    return visitor.end();
  }
  @Override() public double walkInRowOrder(final RealMatrixPreservingVisitor visitor) {
    final int rows = getRowDimension();
    final int columns = getColumnDimension();
    visitor.start(rows, columns, 0, rows - 1, 0, columns - 1);
    for(int i = 0; i < rows; ++i) {
      final double[] rowI = data[i];
      for(int j = 0; j < columns; ++j) {
        visitor.visit(i, j, rowI[j]);
      }
    }
    return visitor.end();
  }
  @Override() public double walkInRowOrder(final RealMatrixPreservingVisitor visitor, final int startRow, final int endRow, final int startColumn, final int endColumn) throws OutOfRangeException, NumberIsTooSmallException {
    MatrixUtils.checkSubMatrixIndex(this, startRow, endRow, startColumn, endColumn);
    visitor.start(getRowDimension(), getColumnDimension(), startRow, endRow, startColumn, endColumn);
    for(int i = startRow; i <= endRow; ++i) {
      final double[] rowI = data[i];
      for(int j = startColumn; j <= endColumn; ++j) {
        visitor.visit(i, j, rowI[j]);
      }
    }
    return visitor.end();
  }
  @Override() public double[] operate(final double[] v) throws DimensionMismatchException {
    final int nRows = this.getRowDimension();
    final int nCols = this.getColumnDimension();
    if(v.length != nCols) {
      throw new DimensionMismatchException(v.length, nCols);
    }
    final double[] out = new double[nRows];
    for(int row = 0; row < nRows; row++) {
      final double[] dataRow = data[row];
      double sum = 0;
      for(int i = 0; i < nCols; i++) {
        sum += dataRow[i] * v[i];
      }
      out[row] = sum;
    }
    return out;
  }
  @Override() public double[] preMultiply(final double[] v) throws DimensionMismatchException {
    final int nRows = getRowDimension();
    final int nCols = getColumnDimension();
    if(v.length != nRows) {
      throw new DimensionMismatchException(v.length, nRows);
    }
    final double[] out = new double[nCols];
    for(int col = 0; col < nCols; ++col) {
      double sum = 0;
      for(int i = 0; i < nRows; ++i) {
        sum += data[i][col] * v[i];
      }
      out[col] = sum;
    }
    return out;
  }
  private double[][] copyOut() {
    final int nRows = this.getRowDimension();
    final double[][] out = new double[nRows][this.getColumnDimension()];
    for(int i = 0; i < nRows; i++) {
      System.arraycopy(data[i], 0, out[i], 0, data[i].length);
    }
    return out;
  }
  @Override() public double[][] getData() {
    return copyOut();
  }
  public double[][] getDataRef() {
    return data;
  }
  @Override() public int getColumnDimension() {
    return ((data == null) || (data[0] == null)) ? 0 : data[0].length;
  }
  @Override() public int getRowDimension() {
    return (data == null) ? 0 : data.length;
  }
  @Override() public void addToEntry(final int row, final int column, final double increment) throws OutOfRangeException {
    MatrixUtils.checkMatrixIndex(this, row, column);
    data[row][column] += increment;
  }
  private void copyIn(final double[][] in) throws DimensionMismatchException, NoDataException, NullArgumentException {
    setSubMatrix(in, 0, 0);
  }
  @Override() public void multiplyEntry(final int row, final int column, final double factor) throws OutOfRangeException {
    MatrixUtils.checkMatrixIndex(this, row, column);
    data[row][column] *= factor;
  }
  @Override() public void setEntry(final int row, final int column, final double value) throws OutOfRangeException {
    MatrixUtils.checkMatrixIndex(this, row, column);
    data[row][column] = value;
  }
  @Override() public void setSubMatrix(final double[][] subMatrix, final int row, final int column) throws NoDataException, OutOfRangeException, DimensionMismatchException, NullArgumentException {
    if(data == null) {
      if(row > 0) {
        throw new MathIllegalStateException(LocalizedFormats.FIRST_ROWS_NOT_INITIALIZED_YET, row);
      }
      if(column > 0) {
        throw new MathIllegalStateException(LocalizedFormats.FIRST_COLUMNS_NOT_INITIALIZED_YET, column);
      }
      MathUtils.checkNotNull(subMatrix);
      final int nRows = subMatrix.length;
      if(nRows == 0) {
        throw new NoDataException(LocalizedFormats.AT_LEAST_ONE_ROW);
      }
      final int nCols = subMatrix[0].length;
      if(nCols == 0) {
        throw new NoDataException(LocalizedFormats.AT_LEAST_ONE_COLUMN);
      }
      data = new double[subMatrix.length][nCols];
      for(int i = 0; i < data.length; ++i) {
        if(subMatrix[i].length != nCols) {
          throw new DimensionMismatchException(subMatrix[i].length, nCols);
        }
        System.arraycopy(subMatrix[i], 0, data[i + row], column, nCols);
      }
    }
    else {
      super.setSubMatrix(subMatrix, row, column);
    }
  }
}