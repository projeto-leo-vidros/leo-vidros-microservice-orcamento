package com.project.extension.infrastructure.adapters;

import com.project.extension.application.ports.PdfGenerator;
import com.project.extension.domain.dto.OrcamentoDTO;
import com.project.extension.domain.dto.OrcamentoItemDTO;
import com.project.extension.domain.dto.ProdutoInstalacaoDTO;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.project.extension.domain.exception.GeracaoPdfException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.awt.Color;

@Component
public class OpenPdfAdapter implements PdfGenerator {

    @Override
    public byte[] generateFromOrcamento(OrcamentoDTO dados) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // --- CABEÇALHO ---
            Font fontTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Paragraph titulo = new Paragraph("LÉO VIDROS - ORÇAMENTO", fontTitulo);
            titulo.setAlignment(Element.ALIGN_CENTER);
            document.add(titulo);

            document.add(new Paragraph("Número: " + dados.numeroOrcamento()));
            document.add(new Paragraph("Data: " + dados.dataOrcamento()));
            document.add(new Paragraph("Cliente: " + dados.cliente().nome()));
            document.add(new Paragraph("E-mail: " + dados.cliente().email()));
            document.add(new Paragraph(" "));

            // --- TABELA DE ITENS ---
            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            table.setSpacingBefore(10f);
            table.setSpacingAfter(10f);
            table.setWidths(new float[]{4f, 1f, 2f, 2f});

            addTableHeader(table);

            for (OrcamentoItemDTO item : dados.itens()) {
                table.addCell(item.descricao());
                table.addCell(String.valueOf(item.quantidade()));
                table.addCell("R$ " + String.format("%.2f", item.precoUnitario()));
                table.addCell("R$ " + String.format("%.2f", item.subtotal()));
            }

            document.add(table);

            // --- TOTAIS E CONDIÇÕES ---
            document.add(new Paragraph("Subtotal: R$ " + String.format("%.2f", dados.valorSubtotal())));
            document.add(new Paragraph("Desconto: R$ " + String.format("%.2f", dados.valorDesconto())));

            Font fontTotal = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            document.add(new Paragraph("TOTAL: R$ " + String.format("%.2f", dados.valorTotal()), fontTotal));

            if (dados.produtosInstalacao() != null && !dados.produtosInstalacao().isEmpty()) {
                document.add(new Paragraph(" "));
                Font fontSecao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
                document.add(new Paragraph("MATERIAIS PREVISTOS PARA INSTALAÇÃO", fontSecao));

                PdfPTable prodTable = new PdfPTable(2);
                prodTable.setWidthPercentage(100);
                prodTable.setSpacingBefore(6f);
                prodTable.setSpacingAfter(10f);
                prodTable.setWidths(new float[]{5f, 1.5f});

                PdfPCell hProd = new PdfPCell();
                hProd.setBackgroundColor(Color.LIGHT_GRAY);
                hProd.setPadding(5);
                Font fontH = FontFactory.getFont(FontFactory.HELVETICA_BOLD);
                hProd.setPhrase(new Phrase("Produto", fontH));
                prodTable.addCell(hProd);
                PdfPCell hQtd = new PdfPCell();
                hQtd.setBackgroundColor(Color.LIGHT_GRAY);
                hQtd.setPadding(5);
                hQtd.setPhrase(new Phrase("Qtd", fontH));
                prodTable.addCell(hQtd);

                for (ProdutoInstalacaoDTO prod : dados.produtosInstalacao()) {
                    prodTable.addCell(prod.nome() != null ? prod.nome() : "");
                    prodTable.addCell(prod.quantidade() != null
                            ? String.format("%.2f", prod.quantidade()) : "0");
                }
                document.add(prodTable);
            }

            document.add(new Paragraph(" "));
            document.add(new Paragraph("--- CONDIÇÕES COMERCIAIS ---"));
            document.add(new Paragraph("Prazo de Instalação: " + dados.prazoInstalacao()));
            document.add(new Paragraph("Garantia: " + dados.garantia()));
            document.add(new Paragraph("Forma de Pagamento: " + dados.formaPagamento()));

            if (dados.observacoes() != null && !dados.observacoes().isEmpty()) {
                document.add(new Paragraph("Observações: " + dados.observacoes()));
            }

            document.close();
        } catch (Exception e) {
            throw new GeracaoPdfException("Falha ao gerar o conteúdo do PDF para o orçamento: " + dados.numeroOrcamento(), e);
        }

        return out.toByteArray();
    }

    private void addTableHeader(PdfPTable table) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(Color.LIGHT_GRAY);
        cell.setPadding(5);

        Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD);

        cell.setPhrase(new Phrase("Descrição", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Qtd", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Unitário", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Subtotal", font));
        table.addCell(cell);
    }
}
