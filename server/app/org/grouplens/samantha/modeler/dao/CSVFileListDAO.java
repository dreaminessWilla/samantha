/*
 * Copyright (c) [2016-2017] [University of Minnesota]
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.grouplens.samantha.modeler.dao;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public class CSVFileListDAO implements EntityDAO {
    final private List<String> fileList;
    final private String separator;
    private int idx = 0;
    private CSVFileDAO csvFileDAO;

    public CSVFileListDAO(List<String> fileList, String separator) {
        this.fileList = fileList;
        this.separator = separator;
    }

    public boolean hasNextEntity() {
        do {
            if (csvFileDAO == null) {
                if (idx >= fileList.size()) {
                    return false;
                }
                csvFileDAO = new CSVFileDAO(separator, fileList.get(idx++));
            }
            if (csvFileDAO.hasNextEntity()) {
                return true;
            } else {
                csvFileDAO.close();
                csvFileDAO = null;
            }
        } while (idx < fileList.size());
        return false;
    }

    public ObjectNode getNextEntity() {
        do {
            if (csvFileDAO == null) {
                if (idx >= fileList.size()) {
                    return null;
                }
                csvFileDAO = new CSVFileDAO(separator, fileList.get(idx++));
            }
            if (csvFileDAO.hasNextEntity()) {
                return csvFileDAO.getNextEntity();
            } else {
                csvFileDAO.close();
                csvFileDAO = null;
            }
        } while (idx < fileList.size());
        return null;
    }

    public void restart() {
        close();
        idx = 0;
    }

    public void close() {
        if (csvFileDAO != null) {
            csvFileDAO.close();
            csvFileDAO = null;
        }
    }
}
