function ScatterplotDialog(column) {
    this._column = column;
    this._plot_method = "lin";
    this._createDialog();
    this._active = true;
}

ScatterplotDialog.prototype._createDialog = function() {
    var self = this;
    var dialog = $(DOM.loadHTML("core", "scripts/dialogs/scatterplot-dialog.html"));
    this._elmts = DOM.bind(dialog);
    this._elmts.dialogHeader.text(
        'Scatterplot Matrix' + 
            ((typeof this._column == "undefined") ? "" : " (focusing on '" + this._column + "')"));

    this._elmts.closeButton.click(function() { self._dismiss(); });
    
    this._elmts.plotSelector.buttonset().change(function() {
        self._plot_method = $(this).find("input:checked").val();
        self._renderMatrix();
    });

    this._elmts.rotationSelector.buttonset().change(function() {
        self._rotation = $(this).find("input:checked").val();
        self._renderMatrix();
    });
    
    this._elmts.dotSelector.buttonset().change(function() {
        var dot_size = $(this).find("input:checked").val();
        if (dot_size == "small") {
            self._dot_size = 0.4;
        } else if (dot_size == "big") {
            self._dot_size = 1.4;
        } else {
            self._dot_size = 0.8;
        }
        self._renderMatrix();
    });
    
    this._level = DialogSystem.showDialog(dialog);
    this._renderMatrix();
};

ScatterplotDialog.prototype._renderMatrix = function() {
    var self = this;
    
    var container = this._elmts.tableContainer.html(
        '<div style="margin: 1em; font-size: 130%; color: #888; background-color: white;">Processing... <img src="/images/small-spinner.gif"></div>'
    );

    if (theProject.columnModel.columns.length > 0) {
        var params = {
            project: theProject.id
        };
        $.getJSON("/command/core/get-columns-info?" + $.param(params),function(data) {
            if (data === null || typeof data.length == 'undefined') {
                container.html("Error calling 'get-columns-info'");
                return;
            }
                
            var columns = [];
            for (var i = 0; i < data.length; i++) {
                if (data[i].is_numeric) {
                    columns.push(data[i]);
                }
            }
                
            if (typeof self._plot_size == 'undefined') {
                self._plot_size = Math.max(Math.floor(500 / columns.length / 5) * 5,20);
                self._dot_size = 0.8;
            }
            
            var table = '<table class="scatterplot-matrix-table"><tbody>';
            
            var createScatterplot = function(cx, cy) {
                var title = cx + ' (x) vs. ' + cy + ' (y)';
                var link = '<a href="javascript:{}" title="' + title + '" cx="' + cx + '" cy="' + cy + '">';
                var plotter_params = { 
                    'cx' : cx, 
                    'cy' : cy,
                    'l' : self._plot_size,
                    'dot': self._dot_size,
                    'dim_x': self._plot_method,
                    'dim_y': self._plot_method,
                    'r': self._rotation
                };
                var params = {
                    project: theProject.id,
                    engine: JSON.stringify(ui.browsingEngine.getJSON()), 
                    plotter: JSON.stringify(plotter_params) 
                };
                var url = "/command/core/get-scatterplot?" + $.param(params);

                var attrs = [
                    'width="' + self._plot_size + '"',
                    'height="' + self._plot_size + '"',
                    'src2="' + url + '"'
                ];
                
                return link + '<img ' + attrs.join(' ') + ' /></a>';
            };
    
            for (var i = 0; i < columns.length; i++) {
                table += '<tr>';
                var div_class = "column_header";
                if (columns[i].name == self._column) div_class += " current_column";
                table += '<td class="' + div_class + '" colspan="' + (i + 1) + '">' + columns[i].name + '</td>';
                for (var j = i + 1; j < columns.length; j++) {
                    var cx = columns[i].name;
                    var cy = columns[j].name;
                    
                    var div_class = "scatterplot";
                    var current = cx == self._column || cy == self._column;
                    if (current) div_class += " current_column";
                    table += '<td><div class="' + div_class + '">' + createScatterplot(cx,cy) + '</div></td>';
                }
                table += '</tr>';
            }
    
            table += "</tbody></table>";
            
            var width = container.width();
            container.empty().css("width", width + "px").html(table);
            
            container.find("a").click(function() {
                var options = {
                    "name" : $(this).attr("title"),
                    "cx" : $(this).attr("cx"), 
                    "cy" : $(this).attr("cy"), 
                    "l" : 150,
                    "ex" : "value",
                    "ey" : "value",
                    "dot" : self._dot_size,
                    "dim_x" : self._plot_method,
                    "dim_y" : self._plot_method,
                    'r': self._rotation
                };
                ui.browsingEngine.addFacet("scatterplot", options);
                self._dismiss();
            });

            var load_images = function(data) {
                if (self._active) {
                    data.batch = 0;
                    var end = Math.min(data.index + data.batch_size,data.images.length);
                    for (; data.index < end; data.index++) {
                        load_image(data);
                    }
                }
            };
            
            var load_image = function(data) {
                var img = $(data.images[data.index]);                
                var src2 = img.attr("src2");
                if (src2) {
                    img.attr("src", src2);
                    img.removeAttr("src2");
                    img.load(function() {
                        data.batch++;
                        if (data.batch == data.batch_size) {
                            load_images(data);
                        }
                    });
                }
            };
            
            load_images({
                index : 0,
                batch_size: 4,
                images : container.find(".scatterplot img")
            });
        });
    } else {
        container.html(
            '<div style="margin: 2em;"><div style="font-size: 130%; color: #333;">There are no columns in this dataset</div></div>'
        );
    }
    
};

ScatterplotDialog.prototype._dismiss = function() {
    this._active = false;
    DialogSystem.dismissUntil(this._level - 1);
};

