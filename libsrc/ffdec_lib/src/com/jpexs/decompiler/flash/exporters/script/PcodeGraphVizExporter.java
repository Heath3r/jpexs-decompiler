/*
 *  Copyright (C) 2010-2021 JPEXS, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.jpexs.decompiler.flash.exporters.script;

import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.avm2.graph.AVM2Graph;
import com.jpexs.decompiler.flash.abc.types.MethodBody;
import com.jpexs.decompiler.flash.action.ActionGraph;
import com.jpexs.decompiler.flash.action.ActionList;
import com.jpexs.decompiler.flash.configuration.Configuration;
import com.jpexs.decompiler.flash.exporters.script.graphviz.AbstractLexer;
import com.jpexs.decompiler.flash.exporters.script.graphviz.Flasm3Lexer;
import com.jpexs.decompiler.flash.exporters.script.graphviz.FlasmLexer;
import com.jpexs.decompiler.flash.exporters.script.graphviz.Token;
import com.jpexs.decompiler.flash.exporters.script.graphviz.TokenType;
import com.jpexs.decompiler.flash.helpers.GraphTextWriter;
import com.jpexs.decompiler.flash.tags.base.ASMSource;
import com.jpexs.decompiler.graph.Graph;
import com.jpexs.decompiler.graph.GraphException;
import com.jpexs.decompiler.graph.GraphPart;
import com.jpexs.decompiler.graph.GraphSource;
import com.jpexs.decompiler.graph.ScopeStack;
import com.jpexs.graphs.graphviz.dot.parser.DotId;
import com.jpexs.helpers.Helper;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author JPEXS
 */
public class PcodeGraphVizExporter {

    private final String BLOCK_STYLE = "shape=\"box\"";

    private static final int INS_LEN_LIMIT = 80;
    private static final String ELIPSIS = "...";

    private String getBlockName(GraphSource list, GraphPart part) {
        return "loc" + Helper.formatAddress(list.pos2adr(part.start, true));
    }

    private boolean isEndOfScript(GraphSource list, GraphPart part) {
        if (part.start >= list.size()) {
            return true;
        }
        return false;
    }

    private static void populateParts(GraphPart part, Set<GraphPart> allParts) {
        if (allParts.contains(part)) {
            return;
        }
        allParts.add(part);
        for (GraphPart p : part.nextParts) {
            populateParts(p, allParts);
        }
    }

    public void exportAs12(ASMSource src, GraphTextWriter writer) throws InterruptedException {
        ActionList alist = src.getActions();
        ActionGraph gr = new ActionGraph("", false, alist, new HashMap<>(), new HashMap<>(), new HashMap<>(), SWF.DEFAULT_VERSION);
        export(gr, writer);
    }

    public void exportAs3(ABC abc, MethodBody body, GraphTextWriter writer) throws InterruptedException {
        AVM2Graph gr = new AVM2Graph(body.getCode(), abc, body, false, -1, -1, new HashMap<>(), new ScopeStack(), new HashMap<>(), new ArrayList<>(), new HashMap<>(), body.getCode().visitCode(body));
        export(gr, writer);
    }

    private void exportGraph(Graph graph, GraphTextWriter writer) throws InterruptedException {
        graph.init(null);
        GraphSource graphSource = graph.getGraphCode();
        Set<GraphPart> allBlocks = new HashSet<>();
        List<GraphPart> heads = graph.heads;
        for (GraphPart h : heads) {
            populateParts(h, allBlocks);
        }
        List<GraphException> exceptions = graph.getExceptions();

        Set<Long> knownAddresses = graphSource.getImportantAddresses();
        int h = 0;
        List<Integer> exPassedStarts = new ArrayList<>();
        List<Integer> exPassedEnds = new ArrayList<>();

        StringBuilder ends = new StringBuilder();
        loopheads:
        for (GraphPart head : heads) {
            String headName = "start";
            if (heads.size() > 1 && head.start != 0) {
                h++;
                headName = "start" + h;
            }
            String headLabel = "";
            List<String> headLabels = new ArrayList<>();
            boolean isExEnd = false;
            boolean isExTarget = false;
            boolean isExStart = false;
            for (int e = 0; e < exceptions.size(); e++) {
                GraphException ex = exceptions.get(e);
                if (head.start == ex.start && !exPassedStarts.contains(e)) {
                    headLabels.add("try " + e + " begin");
                    exPassedStarts.add(e);
                    isExStart = true;
                    break;
                }
                if (head.start == ex.end && !exPassedEnds.contains(e)) {
                    headLabels.add("try " + e + " end");
                    exPassedEnds.add(e);
                    isExEnd = true;
                    break;
                }
                if (head.start == ex.target) {
                    headLabels.add("try " + e + " target");
                    isExTarget = true;
                }
            }
            if (!headLabels.isEmpty()) {
                headLabel = String.join("\\n", headLabels);
            }
            String headDef = headName + " [shape=\"circle\"" + (headLabel.isEmpty() ? "" : " label=\"" + headLabel + "\"") + "]\r\n";

            if (isExEnd) {
                for (int i = head.start - 1; i >= 0; i--) {
                    GraphPart endPrev = graph.searchPart(i, allBlocks);
                    if (endPrev != null) {
                        ends.append(getBlockName(graphSource, endPrev) + ":se -> " + headName + ":nw[dir=back arrowtail=none style=dashed];\r\n");
                        ends.append(headDef);
                        continue loopheads;
                    }
                }
            }
            writer.append(headDef);

            writer.append(headName + ":" + (isExStart ? "se" : "s") + " -> " + getBlockName(graphSource, head) + ":" + (isExStart ? "nw[arrowhead=none style=dashed]" : "n") + ";\r\n");
        }
        for (GraphPart part : allBlocks) {
            StringBuilder blkCodeBuilder = new StringBuilder();
            for (int j = part.start; j <= part.end; j++) {
                if (j < graphSource.size()) {
                    if (knownAddresses.contains(graphSource.get(j).getAddress())) {
                        blkCodeBuilder.append("ofs").append(Helper.formatAddress(graphSource.get(j).getAddress())).append(":\r\n");
                    }
                    String insStr = graphSource.insToString(j);
                    blkCodeBuilder.append(insStr).append("\r\n");
                }
            }
            String labelStr = blkCodeBuilder.toString();
            if (Configuration.showLineNumbersInPCodeGraphvizGraph.get()) {
                labelStr = ";lines " + part.toString() + "\r\n" + labelStr;
            }
            labelStr = hilight(labelStr, graph);

            String partBlockName = getBlockName(graphSource, part);
            String blkStyle = BLOCK_STYLE;

            if (isEndOfScript(graphSource, part)) {
                blkStyle = "shape=\"circle\"";
                labelStr = "FINISH";
            }

            writer.append(partBlockName + " [" + blkStyle + " fontname=\"Courier New\" label=<<TABLE BORDER=\"0\" CELLBORDER=\"0\" CELLSPACING=\"0\"><TR><TD BALIGN=\"LEFT\">" + labelStr + "</TD></TR></TABLE>>];\r\n");
            for (int n = 0; n < part.nextParts.size(); n++) {
                GraphPart next = part.nextParts.get(n);
                String orientation = ":s";
                String color = null;
                if (part.nextParts.size() == 2 && n == 0) {
                    orientation = ":sw";
                    color = "green4";
                }
                if (part.nextParts.size() == 2 && n == 1) {
                    orientation = ":se";
                    color = "red";
                }
                String nextBlockName = getBlockName(graphSource, next);
                writer.append(partBlockName + orientation + " -> " + nextBlockName + ":n" + (color != null ? "[color=\"" + color + "\"]" : "") + ";\r\n");
            }
        }
        writer.append(ends.toString());
    }

    private static String hilight(AbstractLexer lexer, String code) {
        Token t;
        StringBuilder sb = new StringBuilder();
        int lineStart = 0;
        boolean afterElipsis = false;
        int rawLen = 0;
        try {
            while ((t = lexer.yylex()) != null) {
                if (t.type == TokenType.NEWLINE) {
                    sb.append("<BR />");
                    afterElipsis = false;
                    lineStart = rawLen;
                    continue;
                }
                if (afterElipsis) {
                    continue;
                }
                String color = null;
                boolean bold = false;
                switch (t.type) {
                    case KEYWORD:
                        color = "#0000ff";
                        bold = true;
                        break;
                    case KEYWORD2:
                        color = "#007f7f";
                        bold = true;
                        break;
                    case OPERATOR:
                        color = "#7f007f";
                        break;
                    case STRING:
                        color = "#cc6600";
                        break;
                    case COMMENT:
                    case COMMENT2:
                        color = "#339933";
                        break;
                }

                int tlen = t.length;
                boolean tooLong = false;
                int lenFromStartLine = rawLen - lineStart;
                if (lenFromStartLine + tlen > INS_LEN_LIMIT) {
                    int newtlen = INS_LEN_LIMIT - lenFromStartLine;
                    tlen = newtlen;
                    tooLong = true;
                }

                if (color != null && tlen > 0) {
                    sb.append("<FONT color=\"" + color + "\">");
                }
                if (bold && tlen > 0) {
                    sb.append("<B>");
                }

                rawLen += tlen;
                String s = code.substring(t.start, t.start + tlen);
                s = s.replace("&", "&amp;");
                s = s.replace("[", "&#x5B;");
                s = s.replace("]", "&#x5D;"); //ends graphviz parameters block!
                s = s.replace("<", "&lt;");
                s = s.replace(">", "&gt;");
                s = s.replace("\\", "\\\\");
                s = s.replace("\r\n", "<BR />");
                sb.append(s);
                if (bold && tlen > 0) {
                    sb.append("</B>");
                }
                if (color != null && tlen > 0) {
                    sb.append("</FONT>");
                }
                if (tooLong) {
                    sb.append(ELIPSIS);
                    afterElipsis = true;
                }
            }
        } catch (IOException ex) {
            return code;
        }
        return sb.toString();
    }

    private String hilight(String code, Graph graph) {
        AbstractLexer lexer;
        if (graph instanceof ActionGraph) {
            lexer = new FlasmLexer(new StringReader(code));
        } else {
            lexer = new Flasm3Lexer(new StringReader(code));
        }
        return hilight(lexer, code);
    }

    public void export(Graph graph, GraphTextWriter writer) throws InterruptedException {
        writer.append("digraph pcode {\r\n");
        exportGraph(graph, writer);
        int pos = 0;
        Map<String, Graph> subgraphs = graph.getSubGraphs();
        for (String name : subgraphs.keySet()) {
            writer.append("subgraph cluster_" + pos + " {");
            writer.append("label=" + new DotId(name, false) + ";\r\n");
            pos++;
            exportGraph(subgraphs.get(name), writer);
            writer.append("}");
        }
        writer.append("}\r\n");
    }
}
