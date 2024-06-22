/* ===========================================================
 * JFreeChart : a free chart library for the Java(tm) platform
 * ===========================================================
 *
 * (C) Copyright 2000-2009, by Object Refinery Limited and Contributors.
 *
 * Project Info:  http://www.jfree.org/jfreechart/index.html
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * [Java is a trademark or registered trademark of Sun Microsystems, Inc.
 * in the United States and other countries.]
 *
 * ------------------------
 * IntervalBarRenderer.java
 * ------------------------
 * (C) Copyright 2002-2009, by Jeremy Bowman.
 *
 * Original Author:  Jeremy Bowman;
 * Contributor(s):   David Gilbert (for Object Refinery Limited);
 *                   Christian W. Zuckschwerdt;
 *                   Peter Kolb (patch 2497611, 2791407);
 *
 * Changes
 * -------
 * 29-Apr-2002 : Version 1, contributed by Jeremy Bowman (DG);
 * 11-May-2002 : Use CategoryPlot.getLabelsVisible() (JB);
 * 29-May-2002 : Added constructors (DG);
 * 26-Jun-2002 : Added axis to initialise method (DG);
 * 20-Sep-2002 : Added basic support for chart entities (DG);
 * 24-Oct-2002 : Amendments for changes in CategoryDataset interface and
 *               CategoryToolTipGenerator interface (DG);
 * 05-Nov-2002 : Base dataset is now TableDataset not CategoryDataset (DG);
 * 25-Mar-2003 : Implemented Serializable (DG);
 * 30-Jul-2003 : Modified entity constructor (CZ);
 * 19-Aug-2003 : Implemented Cloneable and PublicCloneable (DG);
 * 08-Sep-2003 : Added checks for null values (DG);
 * 07-Oct-2003 : Added renderer state (DG);
 * 21-Oct-2003 : Bar width moved into renderer state (DG);
 * 23-Dec-2003 : Removed the deprecated MultiIntervalCategoryDataset
 *               interface (DG);
 * 05-Nov-2004 : Modified drawItem() signature (DG);
 * 20-Apr-2005 : Renamed CategoryLabelGenerator
 *               --> CategoryItemLabelGenerator (DG);
 * 02-Feb-2007 : Removed author tags all over JFreeChart sources (DG);
 * 20-Jun-2007 : Removed JCommon dependencies (DG);
 * 29-Jun-2007 : Simplified entity generation by calling addEntity() (DG);
 * 06-Jul-2007 : Respect drawBarOutline attribute (DG);
 * 24-Jun-2008 : Added new barPainter mechanism (DG);
 * 07-Oct-2008 : Override equals() method to fix minor bug (DG);
 * 14-Jan-2009 : Added support for seriesVisible flags (PK);
 * 16-May-2009 : The findRangeBounds() method needs to include the dataset
 *               interval (DG);
 * 19-May-2009 : Fixed FindBugs warnings, patch by Michal Wozniak (DG);
 *
 */

package org.jfree.chart.renderer.category;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.entity.EntityCollection;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.util.RectangleEdge;
import org.jfree.data.Range;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.IntervalCategoryDataset;

/**
 * A renderer that handles the drawing of bars for a bar plot where
 * each bar has a high and low value.  This renderer is for use with the
 * {@link CategoryPlot} class.  The example shown here is generated by the
 * <code>IntervalBarChartDemo1.java</code> program included in the JFreeChart
 * Demo Collection:
 * <br><br>
 * <img src="../../../../../images/IntervalBarRendererSample.png"
 * alt="IntervalBarRendererSample.png" />
 */
public class IntervalBarRenderer extends BarRenderer {

    /** For serialization. */
    private static final long serialVersionUID = -5068857361615528725L;

    /**
     * Constructs a new renderer.
     */
    public IntervalBarRenderer() {
        super();
    }

    /**
     * Returns the range of values from the specified dataset.  For this
     * renderer, this is equivalent to calling
     * <code>findRangeBounds(dataset, true)</code>.
     *
     * @param dataset  the dataset (<code>null</code> permitted).
     *
     * @return The range (or <code>null</code> if the dataset is
     *         <code>null</code> or empty).
     */
    public Range findRangeBounds(CategoryDataset dataset) {
        return findRangeBounds(dataset, true);
    }

    /**
     * Draws the bar for a single (series, category) data item.
     *
     * @param g2  the graphics device.
     * @param state  the renderer state.
     * @param dataArea  the data area.
     * @param plot  the plot.
     * @param domainAxis  the domain axis.
     * @param rangeAxis  the range axis.
     * @param dataset  the dataset.
     * @param row  the row index (zero-based).
     * @param column  the column index (zero-based).
     * @param pass  the pass index.
     */
    public void drawItem(Graphics2D g2, CategoryItemRendererState state,
            Rectangle2D dataArea, CategoryPlot plot, CategoryAxis domainAxis,
            ValueAxis rangeAxis, CategoryDataset dataset, int row, int column,
            boolean selected, int pass) {

         if (dataset instanceof IntervalCategoryDataset) {
             IntervalCategoryDataset d = (IntervalCategoryDataset) dataset;
             drawInterval(g2, state, dataArea, plot, domainAxis, rangeAxis,
                     d, row, column, selected);
         }
         else {
             super.drawItem(g2, state, dataArea, plot, domainAxis, rangeAxis,
                     dataset, row, column, selected, pass);
         }

     }

     /**
      * Draws a single interval.
      *
      * @param g2  the graphics device.
      * @param state  the renderer state.
      * @param dataArea  the data plot area.
      * @param plot  the plot.
      * @param domainAxis  the domain axis.
      * @param rangeAxis  the range axis.
      * @param dataset  the data.
      * @param row  the row index (zero-based).
      * @param column  the column index (zero-based).
      * @param selected  is the item selected?
      *
      * @since 1.2.0
      */
     protected void drawInterval(Graphics2D g2, 
             CategoryItemRendererState state, Rectangle2D dataArea,
             CategoryPlot plot, CategoryAxis domainAxis, ValueAxis rangeAxis,
             IntervalCategoryDataset dataset, int row, int column,
             boolean selected) {

        int visibleRow = state.getVisibleSeriesIndex(row);
        if (visibleRow < 0) {
            return;
        }
        int seriesCount = state.getVisibleSeriesCount() >= 0
                ? state.getVisibleSeriesCount() : getRowCount();

        int categoryCount = getColumnCount();

        PlotOrientation orientation = plot.getOrientation();

        double rectX = 0.0;
        double rectY = 0.0;

        RectangleEdge domainAxisLocation = plot.getDomainAxisEdge();
        RectangleEdge rangeAxisLocation = plot.getRangeAxisEdge();

        // Y0
        Number value0 = dataset.getEndValue(row, column);
        if (value0 == null) {
            return;
        }
        double java2dValue0 = rangeAxis.valueToJava2D(value0.doubleValue(),
                dataArea, rangeAxisLocation);

        // Y1
        Number value1 = dataset.getStartValue(row, column);
        if (value1 == null) {
            return;
        }
        double java2dValue1 = rangeAxis.valueToJava2D(
                value1.doubleValue(), dataArea, rangeAxisLocation);

        if (java2dValue1 < java2dValue0) {
            double temp = java2dValue1;
            java2dValue1 = java2dValue0;
            java2dValue0 = temp;
        }

        // BAR WIDTH
        double rectWidth = state.getBarWidth();

        // BAR HEIGHT
        double rectHeight = Math.abs(java2dValue1 - java2dValue0);

        RectangleEdge var_2514 = RectangleEdge.LEFT;
		RectangleEdge barBase = var_2514;
        if (orientation == PlotOrientation.HORIZONTAL) {
            // BAR Y
            rectY = domainAxis.getCategoryStart(column, getColumnCount(),
                    dataArea, domainAxisLocation);
            if (seriesCount > 1) {
                double seriesGap = dataArea.getHeight() * getItemMargin()
                                   / (categoryCount * (seriesCount - 1));
                rectY = rectY + visibleRow * (state.getBarWidth() + seriesGap);
            }
            else {
                rectY = rectY + visibleRow * state.getBarWidth();
            }

            rectX = java2dValue0;

            rectHeight = state.getBarWidth();
            rectWidth = Math.abs(java2dValue1 - java2dValue0);
            barBase = RectangleEdge.LEFT;
        }
        else if (orientation == PlotOrientation.VERTICAL) {
            // BAR X
            rectX = domainAxis.getCategoryStart(column, getColumnCount(),
                    dataArea, domainAxisLocation);

            if (seriesCount > 1) {
                double seriesGap = dataArea.getWidth() * getItemMargin()
                                   / (categoryCount * (seriesCount - 1));
                rectX = rectX + visibleRow * (state.getBarWidth() + seriesGap);
            }
            else {
                rectX = rectX + visibleRow * state.getBarWidth();
            }

            rectY = java2dValue0;
            barBase = RectangleEdge.BOTTOM;
        }
        Rectangle2D bar = new Rectangle2D.Double(rectX, rectY, rectWidth,
                rectHeight);
        BarPainter painter = getBarPainter();
        if (getShadowsVisible()) {
            painter.paintBarShadow(g2, this, row, column, selected, bar,
                    barBase, false);
        }
        getBarPainter().paintBar(g2, this, row, column, selected, bar, barBase);

        CategoryItemLabelGenerator generator = getItemLabelGenerator(row,
                column, selected);
        if (generator != null && isItemLabelVisible(row, column, selected)) {
            drawItemLabelForBar(g2, plot, dataset, row, column, selected,
                    generator, bar, false);
        }

        // add an item entity, if this information is being collected
        EntityCollection entities = state.getEntityCollection();
        if (entities != null) {
            addEntity(entities, bar, dataset, row, column, selected);
        }

    }

    /**
     * Tests this renderer for equality with an arbitrary object.
     *
     * @param obj  the object (<code>null</code> permitted).
     *
     * @return A boolean.
     */
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof IntervalBarRenderer)) {
            return false;
        }
        // there are no fields to check
        return super.equals(obj);
    }

}
