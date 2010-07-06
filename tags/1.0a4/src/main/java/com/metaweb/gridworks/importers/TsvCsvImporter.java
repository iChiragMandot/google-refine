package com.metaweb.gridworks.importers;

import java.io.InputStream;
import java.io.LineNumberReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;

import com.metaweb.gridworks.importers.parsers.CSVRowParser;
import com.metaweb.gridworks.importers.parsers.RowParser;
import com.metaweb.gridworks.importers.parsers.SeparatorRowParser;
import com.metaweb.gridworks.model.Column;
import com.metaweb.gridworks.model.Project;
import com.metaweb.gridworks.model.Row;

public class TsvCsvImporter implements Importer {

    public void read(Reader reader, Project project, Properties options, int skip, int limit)
            throws Exception {
        
        LineNumberReader lnReader = new LineNumberReader(reader);
        String      sep = options.getProperty("separator"); // auto-detect if not present
        String      line = null;
        boolean     first = true;
        int         cellCount = 1;
        RowParser   parser = (sep == null || (sep.length() == 0)) ? null : new SeparatorRowParser(sep);
        
        int rowsWithData = 0;
        while ((line = lnReader.readLine()) != null) {
            if (line.trim().length() == 0) {
                continue;
            }
            
            if (parser == null) {
                int tab = line.indexOf('\t');
                if (tab >= 0) {
                    sep = "\t";
                    parser = new SeparatorRowParser(sep);
                } else {
                    sep = ",";
                    parser = new CSVRowParser();
                }
            }
            
            if (first) {
                String[] cells = StringUtils.splitPreserveAllTokens(line, sep);
                Map<String, Integer> nameToIndex = new HashMap<String, Integer>();
                                    
                first = false;
                for (int c = 0; c < cells.length; c++) {
                    String cell = cells[c];
                    if (cell.startsWith("\"") && cell.endsWith("\"")) {
                        cell = cell.substring(1, cell.length() - 1);
                    }
                    
                    if (nameToIndex.containsKey(cell)) {
                    	int index = nameToIndex.get(cell);
                    	nameToIndex.put(cell, index + 1);
                    	
                    	cell = cell.contains(" ") ? (cell + " " + index) : (cell + index);
                    } else {
                    	nameToIndex.put(cell, 2);
                    }
                    
                    Column column = new Column(c, cell);
                    
                    project.columnModel.columns.add(column);
                }
                
                cellCount = cells.length;
            } else {
                Row row = new Row(cellCount);
                
                if (parser.parseRow(row, line)) {
                    rowsWithData++;
                    
                    if (skip <= 0 || rowsWithData > skip) {
                        project.rows.add(row);
                        project.columnModel.setMaxCellIndex(row.cells.size());
                        
                        if (limit > 0 && project.rows.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        }
    }

    public void read(InputStream inputStream, Project project,
            Properties options, int skip, int limit) throws Exception {
        
        throw new NotImplementedException();
    }

    public boolean takesReader() {
        return true;
    }
}