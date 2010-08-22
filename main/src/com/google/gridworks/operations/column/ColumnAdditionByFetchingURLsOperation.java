package com.google.gridworks.operations.column;

import java.io.InputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONWriter;

import com.google.gridworks.browsing.Engine;
import com.google.gridworks.browsing.FilteredRows;
import com.google.gridworks.browsing.RowVisitor;
import com.google.gridworks.expr.EvalError;
import com.google.gridworks.expr.Evaluable;
import com.google.gridworks.expr.ExpressionUtils;
import com.google.gridworks.expr.MetaParser;
import com.google.gridworks.expr.WrappedCell;
import com.google.gridworks.history.HistoryEntry;
import com.google.gridworks.model.AbstractOperation;
import com.google.gridworks.model.Cell;
import com.google.gridworks.model.Column;
import com.google.gridworks.model.Project;
import com.google.gridworks.model.Row;
import com.google.gridworks.model.changes.CellAtRow;
import com.google.gridworks.model.changes.ColumnAdditionChange;
import com.google.gridworks.operations.EngineDependentOperation;
import com.google.gridworks.operations.OnError;
import com.google.gridworks.operations.OperationRegistry;
import com.google.gridworks.operations.cell.TextTransformOperation;
import com.google.gridworks.process.LongRunningProcess;
import com.google.gridworks.process.Process;
import com.google.gridworks.util.ParsingUtilities;

public class ColumnAdditionByFetchingURLsOperation extends EngineDependentOperation {
    final protected String     _baseColumnName;
    final protected String     _urlExpression;
    final protected OnError    _onError;
    
    final protected String     _newColumnName;
    final protected int        _columnInsertIndex;
    final protected int        _delay;

    static public AbstractOperation reconstruct(Project project, JSONObject obj) throws Exception {
        JSONObject engineConfig = obj.getJSONObject("engineConfig");
        
        return new ColumnAdditionByFetchingURLsOperation(
            engineConfig,
            obj.getString("baseColumnName"),
            obj.getString("urlExpression"),
            TextTransformOperation.stringToOnError(obj.getString("onError")),
            obj.getString("newColumnName"),
            obj.getInt("columnInsertIndex"),
            obj.getInt("delay")
        );
    }
    
    public ColumnAdditionByFetchingURLsOperation(
        JSONObject     engineConfig,
        String         baseColumnName,
        String         urlExpression,
        OnError        onError,
        String         newColumnName, 
        int            columnInsertIndex,
        int            delay
    ) {
        super(engineConfig);
        
        _baseColumnName = baseColumnName;
        _urlExpression = urlExpression;
        _onError = onError;
        
        _newColumnName = newColumnName;
        _columnInsertIndex = columnInsertIndex;
        
        _delay = delay;
    }

    public void write(JSONWriter writer, Properties options)
            throws JSONException {
        
        writer.object();
        writer.key("op"); writer.value(OperationRegistry.s_opClassToName.get(this.getClass()));
        writer.key("description"); writer.value(getBriefDescription(null));
        writer.key("engineConfig"); writer.value(getEngineConfig());
        writer.key("newColumnName"); writer.value(_newColumnName);
        writer.key("columnInsertIndex"); writer.value(_columnInsertIndex);
        writer.key("baseColumnName"); writer.value(_baseColumnName);
        writer.key("urlExpression"); writer.value(_urlExpression);
        writer.key("onError"); writer.value(TextTransformOperation.onErrorToString(_onError));
        writer.key("delay"); writer.value(_delay);
        writer.endObject();
    }

    protected String getBriefDescription(Project project) {
        return "Create column " + _newColumnName + 
            " at index " + _columnInsertIndex + 
            " by fetching URLs based on column " + _baseColumnName + 
            " using expression " + _urlExpression;
    }

    protected String createDescription(Column column, List<CellAtRow> cellsAtRows) {
        return "Create new column " + _newColumnName + 
            ", filling " + cellsAtRows.size() +
            " rows by fetching URLs based on column " + column.getName() + 
            " and formulated as " + _urlExpression;
    }
    
    
    public Process createProcess(Project project, Properties options) throws Exception {
        Column column = project.columnModel.getColumnByName(_baseColumnName);
        if (column == null) {
            throw new Exception("No column named " + _baseColumnName);
        }
        if (project.columnModel.getColumnByName(_newColumnName) != null) {
            throw new Exception("Another column already named " + _newColumnName);
        }
        
        Engine engine = createEngine(project);
        engine.initializeFromJSON(_engineConfig);
        
        Evaluable eval = MetaParser.parse(_urlExpression);
        
        return new ColumnAdditionByFetchingURLsProcess(
            project, 
            engine,
            eval,
            getBriefDescription(null)
        );
    }
    
    public class ColumnAdditionByFetchingURLsProcess extends LongRunningProcess implements Runnable {
        final protected Project     _project;
        final protected Engine      _engine;
        final protected Evaluable   _eval;
        final protected long        _historyEntryID;
        protected int               _cellIndex;

        public ColumnAdditionByFetchingURLsProcess(
            Project project, 
            Engine engine,
            Evaluable eval,
            String description
        ) throws JSONException {
            super(description);
            _project = project;
            _engine = engine;
            _eval = eval;
            _historyEntryID = HistoryEntry.allocateID();
        }
        
        public void write(JSONWriter writer, Properties options)
                throws JSONException {
            
            writer.object();
            writer.key("id"); writer.value(hashCode());
            writer.key("description"); writer.value(_description);
            writer.key("immediate"); writer.value(false);
            writer.key("status"); writer.value(_thread == null ? "pending" : (_thread.isAlive() ? "running" : "done"));
            writer.key("progress"); writer.value(_progress);
            writer.endObject();
        }
        
        protected Runnable getRunnable() {
            return this;
        }
        
        public void run() {
            List<CellAtRow> urls = new ArrayList<CellAtRow>(_project.rows.size());
            
            FilteredRows filteredRows = _engine.getAllFilteredRows();
            filteredRows.accept(_project, createRowVisitor(urls));
            
            List<CellAtRow> responseBodies = new ArrayList<CellAtRow>(urls.size());
            for (int i = 0; i < urls.size(); i++) {
                CellAtRow urlData = urls.get(i);
                CellAtRow cellAtRow = fetch(urlData);
                if (cellAtRow != null) {
                    responseBodies.add(cellAtRow);
                }
                
                _progress = i * 100 / urls.size();
                try {
                    Thread.sleep(_delay);
                } catch (InterruptedException e) {
                    if (_canceled) {
                        break;
                    }
                }
            }
            
            if (!_canceled) {
                
                HistoryEntry historyEntry = new HistoryEntry(
                    _historyEntryID,
                    _project, 
                    _description, 
                    ColumnAdditionByFetchingURLsOperation.this, 
                    new ColumnAdditionChange(
                        _newColumnName,
                        _columnInsertIndex,
                        responseBodies)
                );
                
                _project.history.addEntry(historyEntry);
                _project.processManager.onDoneProcess(this);
            }
        }
        
        CellAtRow fetch(CellAtRow urlData) {
            String urlString = urlData.cell.value.toString();
            URL url = null;
            
            try {
                url = new URL(urlString);
            } catch (MalformedURLException e) {
                return null;
            }
            
            try {
                InputStream is = url.openStream();
                try {
                    return new CellAtRow(urlData.row, new Cell(ParsingUtilities.inputStreamToString(is), null));
                } finally {
                    is.close();
                }
            } catch (Exception e) {
                return _onError == OnError.StoreError ?
                        new CellAtRow(urlData.row, new Cell(new EvalError(e.getMessage()), null)) : null;
            }
        }

        RowVisitor createRowVisitor(List<CellAtRow> cellsAtRows) {
            return new RowVisitor() {
                int              cellIndex;
                Properties       bindings;
                List<CellAtRow>  cellsAtRows;
                
                public RowVisitor init(List<CellAtRow> cellsAtRows) {
                    Column column = _project.columnModel.getColumnByName(_baseColumnName);
                    
                    this.cellIndex = column.getCellIndex();
                    this.bindings = ExpressionUtils.createBindings(_project);
                    this.cellsAtRows = cellsAtRows;
                    return this;
                }
                
                @Override
                public void start(Project project) {
                    // nothing to do
                }
                
                @Override
                public void end(Project project) {
                    // nothing to do
                }
                
                public boolean visit(Project project, int rowIndex, Row row) {
                    Cell cell = row.getCell(cellIndex);
                    Cell newCell = null;
                    
                    ExpressionUtils.bind(bindings, row, rowIndex, _baseColumnName, cell);
                    
                    Object o = _eval.evaluate(bindings);
                    if (o != null) {
                        if (o instanceof Cell) {
                            newCell = (Cell) o;
                        } else if (o instanceof WrappedCell) {
                            newCell = ((WrappedCell) o).cell;
                        } else {
                            Serializable v = ExpressionUtils.wrapStorable(o);
                            if (ExpressionUtils.isNonBlankData(v)) {
                                newCell = new Cell(v.toString(), null);
                            }
                        }
                    }
                    
                    if (newCell != null) {
                        cellsAtRows.add(new CellAtRow(rowIndex, newCell));
                    }
                    
                    return false;
                }
            }.init(cellsAtRows);
        }
    }
}
