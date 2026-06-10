package com.project.extension.infrastructure.adapters;

import com.project.extension.application.ports.PdfGenerator;
import com.project.extension.domain.dto.OrcamentoDTO;
import com.project.extension.domain.dto.OrcamentoItemDTO;
import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;


import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocxTemplateAdapter implements PdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocxTemplateAdapter.class);

    private final ResourceLoader resourceLoader;

    @Value("${app.orcamento.template-path}")
    private String templatePath;

    private byte[] templateBytes;

    public DocxTemplateAdapter(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() throws IOException {
        try (InputStream is = resourceLoader.getResource(templatePath).getInputStream()) {
            this.templateBytes = is.readAllBytes();
            log.info("[DocxTemplate] Template carregado — path='{}' tamanho={}KB",
                    templatePath, templateBytes.length / 1024);
        }
    }

    @Override
    public byte[] generateFromOrcamento(OrcamentoDTO orcamento) {
        String numero = orcamento.numeroOrcamento();
        Long id = orcamento.id();
        String cliente = orcamento.cliente() != null ? orcamento.cliente().nome() : "N/A";
        int qtdItens = orcamento.itens() != null ? orcamento.itens().size() : 0;

        log.info("[DocxTemplate] Iniciando geração — numero={} id={} cliente='{}' itens={}",
                numero, id, cliente, qtdItens);

        long inicio = System.currentTimeMillis();
        try {
            XWPFDocument documento = new XWPFDocument(new ByteArrayInputStream(templateBytes));
            Map<String, String> variaveis = construirVariaveis(orcamento);

            for (XWPFParagraph paragrafo : documento.getParagraphs()) {
                substituirNoParagrafo(paragrafo, variaveis);
            }

            for (XWPFTable tabela : documento.getTables()) {
                centralizarVerticalmentePorPlaceholder(tabela, "${nome_cliente}");
                processTable(tabela, orcamento, variaveis);
            }

            preservarBordasTabela(documento);
            byte[] pdf = converterParaPdf(documento);

            long duracao = System.currentTimeMillis() - inicio;
            log.info("[DocxTemplate] PDF gerado — numero={} id={} tamanho={}KB duracao={}ms",
                    numero, id, pdf.length / 1024, duracao);

            return pdf;

        } catch (Exception e) {
            long duracao = System.currentTimeMillis() - inicio;
            log.error("[DocxTemplate] Falha na geração — numero={} id={} duracao={}ms motivo='{}'",
                    numero, id, duracao, e.getMessage(), e);
            throw new RuntimeException("Falha na geração do PDF via DOCX: " + e.getMessage(), e);
        }
    }

    private void processTable(XWPFTable table, OrcamentoDTO orcamento, Map<String, String> vars) {
        int templateRowIndex = -1;
        List<XWPFTableRow> rows = table.getRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rowHasItemPlaceholder(rows.get(i))) {
                templateRowIndex = i;
                break;
            }
        }

        if (templateRowIndex < 0) {
            for (XWPFTableRow row : rows) {
                substituirNaLinha(row, vars);
            }
            return;
        }

        for (int i = 0; i < rows.size(); i++) {
            if (i != templateRowIndex) {
                substituirNaLinha(rows.get(i), vars);
                if (i > 0) centralizarCelulas(rows.get(i));
            }
        }

        XWPFTableRow linhaTemplate = table.getRow(templateRowIndex);
        List<OrcamentoItemDTO> itens = orcamento.itens();

        for (int i = itens.size() - 1; i >= 0; i--) {
            CTRow linhaClonada = (CTRow) linhaTemplate.getCtRow().copy();
            table.addRow(new XWPFTableRow(linhaClonada, table), templateRowIndex);
            substituirNaLinha(table.getRow(templateRowIndex), construirVariaveisItem(itens.get(i)), true);
            centralizarCelulas(table.getRow(templateRowIndex));
        }

        table.removeRow(templateRowIndex + itens.size());
    }

    private void centralizarVerticalmentePorPlaceholder(XWPFTable table, String placeholder) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                String texto = cell.getParagraphs().stream()
                        .flatMap(p -> p.getRuns().stream())
                        .map(r -> r.getText(0) != null ? r.getText(0) : "")
                        .collect(java.util.stream.Collectors.joining());
                if (texto.contains(placeholder)) {
                    CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                            ? cell.getCTTc().getTcPr()
                            : cell.getCTTc().addNewTcPr();
                    if (tcPr.isSetVAlign()) tcPr.unsetVAlign();
                    tcPr.addNewVAlign().setVal(STVerticalJc.CENTER);
                }
            }
        }
    }

    private void centralizarCelulas(XWPFTableRow linha) {
        for (XWPFTableCell celula : linha.getTableCells()) {
            CTTcPr tcPr = celula.getCTTc().isSetTcPr()
                    ? celula.getCTTc().getTcPr()
                    : celula.getCTTc().addNewTcPr();
            if (tcPr.isSetVAlign()) tcPr.unsetVAlign();
            tcPr.addNewVAlign().setVal(STVerticalJc.CENTER);
            if (tcPr.isSetNoWrap()) tcPr.unsetNoWrap();

            for (XWPFParagraph paragrafo : celula.getParagraphs()) {
                paragrafo.setAlignment(ParagraphAlignment.CENTER);
            }
        }
    }

    private void substituirNaLinha(XWPFTableRow linha, Map<String, String> variaveis) {
        substituirNaLinha(linha, variaveis, false);
    }

    private void substituirNaLinha(XWPFTableRow linha, Map<String, String> variaveis, boolean resetCorBranca) {
        for (XWPFTableCell celula : linha.getTableCells()) {
            for (XWPFParagraph paragrafo : celula.getParagraphs()) {
                substituirNoParagrafo(paragrafo, variaveis, resetCorBranca);
            }
        }
    }

    private void substituirNoParagrafo(XWPFParagraph paragrafo, Map<String, String> variaveis) {
        substituirNoParagrafo(paragrafo, variaveis, false);
    }

    private void substituirNoParagrafo(XWPFParagraph paragrafo, Map<String, String> variaveis, boolean resetCorBranca) {
        List<XWPFRun> runs = paragrafo.getRuns();
        if (runs == null || runs.isEmpty())
            return;

        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) {
            String t = run.getText(0);
            if (t != null) sb.append(t);
        }
        String full = sb.toString();
        if (!full.contains("${")) return;

        String merged = full;
        for (Map.Entry<String, String> e : variaveis.entrySet()) {
            if (merged.contains(e.getKey())) {
                merged = merged.replace(e.getKey(), e.getValue());
            }
        }
        if (merged.equals(full)) return;

        XWPFRun primeiraRun = runs.get(0);
        primeiraRun.setText(merged, 0);

        if (resetCorBranca) {
            String corAtual = primeiraRun.getColor();
            if ("FFFFFF".equalsIgnoreCase(corAtual) || "ffffff".equalsIgnoreCase(corAtual)) {
                primeiraRun.setColor("000000");
            }
        }

        for (int i = 1; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            while (run.getCTR().sizeOfTArray() > 0) {
                run.getCTR().removeT(0);
            }
            if (run.getCTR().sizeOfBrArray() > 0) {
                for (int b = run.getCTR().sizeOfBrArray() - 1; b >= 0; b--) {
                    run.getCTR().removeBr(b);
                }
            }
        }
    }

    private boolean rowHasItemPlaceholder(XWPFTableRow row) {
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph p : cell.getParagraphs()) {
                StringBuilder sb = new StringBuilder();
                for (XWPFRun run : p.getRuns()) {
                    for (int i = 0; i < run.getCTR().sizeOfTArray(); i++) {
                        String t = run.getText(i);
                        if (t != null) sb.append(t);
                    }
                }
                if (sb.toString().contains("${itens.")) return true;
            }
        }
        return false;
    }

    private void preservarBordasTabela(XWPFDocument doc) {
        for (XWPFTable tabela : doc.getTables()) {
            if (!isTabelaSemBorda(tabela)) continue;
            for (XWPFTableRow linha : tabela.getRows()) {
                for (XWPFTableCell celula : linha.getTableCells()) {
                    forcarBordasNulasCelula(celula);
                }
            }
        }
    }

    private boolean isTabelaSemBorda(XWPFTable tabela) {
        CTTblPr tblPr = tabela.getCTTbl().getTblPr();
        if (tblPr == null || !tblPr.isSetTblBorders()) return false;
        CTTblBorders b = tblPr.getTblBorders();
        return isBordaNula(b.getTop()) && isBordaNula(b.getBottom())
                && isBordaNula(b.getLeft()) && isBordaNula(b.getRight())
                && isBordaNula(b.getInsideH()) && isBordaNula(b.getInsideV());
    }

    private boolean isBordaNula(CTBorder borda) {
        if (borda == null) return true;
        STBorder.Enum val = borda.getVal();
        if (val == null) return true;
        String v = val.toString();
        return "nil".equals(v) || "none".equals(v);
    }

    private void forcarBordasNulasCelula(XWPFTableCell celula) {
        CTTcPr tcPr = celula.getCTTc().isSetTcPr()
                ? celula.getCTTc().getTcPr()
                : celula.getCTTc().addNewTcPr();
        CTTcBorders tcb = tcPr.isSetTcBorders()
                ? tcPr.getTcBorders()
                : tcPr.addNewTcBorders();
        configurarBordaNula(tcb.isSetTop()     ? tcb.getTop()     : tcb.addNewTop());
        configurarBordaNula(tcb.isSetBottom()  ? tcb.getBottom()  : tcb.addNewBottom());
        configurarBordaNula(tcb.isSetLeft()    ? tcb.getLeft()    : tcb.addNewLeft());
        configurarBordaNula(tcb.isSetRight()   ? tcb.getRight()   : tcb.addNewRight());
        configurarBordaNula(tcb.isSetInsideH() ? tcb.getInsideH() : tcb.addNewInsideH());
        configurarBordaNula(tcb.isSetInsideV() ? tcb.getInsideV() : tcb.addNewInsideV());
    }

    private void configurarBordaNula(CTBorder b) {
        b.setVal(STBorder.NIL);
        b.setSz(BigInteger.ZERO);
        b.setSpace(BigInteger.ZERO);
    }

    private byte[] converterParaPdf(XWPFDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfConverter.getInstance().convert(doc, out, PdfOptions.create());
        doc.close();
        return out.toByteArray();
    }

    private Map<String, String> construirVariaveis(OrcamentoDTO o) {
        Map<String, String> variaveis = new HashMap<>();
        variaveis.put("${nome_cliente}", o.cliente().nome());
        variaveis.put("${numero_orcamento}", o.numeroOrcamento());
        variaveis.put("${data_orcamento}", formatarData(o.dataOrcamento()));
        variaveis.put("${valor_subtotal}", formatarValor(o.valorSubtotal()));
        variaveis.put("${desconto_total}", formatarValor(o.valorDesconto()));
        variaveis.put("${valor_total}", formatarValor(o.valorTotal()));
        variaveis.put("${prazo_instalacao}", o.prazoInstalacao() != null ? o.prazoInstalacao() : "");
        variaveis.put("${garantia}", o.garantia() != null ? o.garantia() : "");
        variaveis.put("${forma_pagamento}", o.formaPagamento() != null ? o.formaPagamento() : "");
        variaveis.put("${observacoes}", o.observacoes() != null ? o.observacoes() : "");

        return variaveis;
    }

    private Map<String, String> construirVariaveisItem(OrcamentoItemDTO item) {
        Map<String, String> variaveis = new HashMap<>();
        variaveis.put("${itens.codigo}", "");
        variaveis.put("${itens.quantidade}", formatarQuantidade(item.quantidade()));
        variaveis.put("${itens.produto}", item.descricao() != null ? item.descricao() : "");
        variaveis.put("${itens.preco_unitario}", formatarValor(item.precoUnitario()));
        variaveis.put("${itens.valor_total_produto}", formatarValor(item.subtotal()));
        variaveis.put("${itens.desconto_produto}", formatarValor(item.desconto()));
        return variaveis;
    }

    private String formatarData(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return "";
        try {
            return LocalDate.parse(isoDate.substring(0, 10))
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return isoDate;
        }
    }

    private String formatarValor(Double valor) {
        return valor == null ? "R$ 0,00" : String.format("R$ %.2f", valor).replace(".", ",");
    }

    private String formatarQuantidade(Double quantidade) {
        if (quantidade == null) return "0";
        return quantidade % 1 == 0
                ? String.valueOf(quantidade.intValue())
                : String.valueOf(quantidade);
    }
}