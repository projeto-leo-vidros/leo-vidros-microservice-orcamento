package com.project.extension.infrastructure.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.extension.application.ports.PdfGenerator;
import com.project.extension.domain.dto.OrcamentoDTO;
import com.project.extension.domain.dto.OrcamentoItemDTO;
import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class ExcelTemplateAdapter implements PdfGenerator {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Value("${app.orcamento.template-path}")
    private String templatePath;

    @Value("${convertapi.key}")
    private String convertApiKey;

    private byte[] templateBytes;
    private HttpClient httpClient;

    public ExcelTemplateAdapter(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        try (InputStream is = resourceLoader.getResource(templatePath).getInputStream()) {
            this.templateBytes = is.readAllBytes();
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public byte[] generateFromOrcamento(OrcamentoDTO orcamento) {
        try {
            byte[] excelBytes = preencherExcel(orcamento);
            return converterViaApiExterna(excelBytes);
        } catch (Exception e) {
            throw new RuntimeException("Falha na geração via Excel/API: " + e.getMessage(), e);
        }
    }

    private byte[] preencherExcel(OrcamentoDTO dto) throws IOException {
        try (InputStream is = new ByteArrayInputStream(templateBytes);
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            preencherCabecalho(sheet, dto);
            preencherItens(sheet, dto.itens());
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] converterViaApiExterna(byte[] excelBytes) throws Exception {
        if (convertApiKey == null || convertApiKey.isBlank()) {
            throw new IllegalStateException("CONVERTAPI_KEY não configurada — defina a propriedade convertapi.key");
        }

        String base64Excel = Base64.getEncoder().encodeToString(excelBytes);

        Map<String, Object> fileValue = Map.of("Name", "orcamento.xlsx", "Data", base64Excel);
        Map<String, Object> param = Map.of("Name", "File", "FileValue", fileValue);
        String jsonBody = objectMapper.writeValueAsString(Map.of("Parameters", List.of(param)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://v2.convertapi.com/convert/xlsx/to/pdf"))
                .header("Authorization", "Bearer " + convertApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Erro na ConvertAPI HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode files = root.path("Files");
        if (!files.isArray() || files.size() == 0) {
            throw new RuntimeException("Resposta inválida da ConvertAPI: array Files ausente/vazio");
        }

        JsonNode fileData = files.get(0).path("FileData");
        if (fileData.isMissingNode() || fileData.asText().isBlank()) {
            throw new RuntimeException("Resposta inválida da ConvertAPI: campo FileData ausente");
        }

        return Base64.getDecoder().decode(fileData.asText());
    }

    private void preencherCabecalho(Sheet sheet, OrcamentoDTO dto) {
        getCell(sheet, 1, 5).setCellValue(dto.dataOrcamento());
        getCell(sheet, 11, 1).setCellValue(dto.cliente().nome());
        getCell(sheet, 11, 5).setCellValue(dto.numeroOrcamento());
    }

    private void preencherItens(Sheet sheet, List<OrcamentoItemDTO> itens) {
        int linhaInicial = 18;
        for (int i = 0; i < itens.size() && i < 14; i++) {
            OrcamentoItemDTO item = itens.get(i);
            getCell(sheet, linhaInicial + i, 1).setCellValue(item.quantidade());
            getCell(sheet, linhaInicial + i, 2).setCellValue(item.descricao());
            getCell(sheet, linhaInicial + i, 3).setCellValue(item.precoUnitario());
        }
    }

    private Row getRow(Sheet sheet, int rowIdx) {
        Row row = sheet.getRow(rowIdx);
        return (row == null) ? sheet.createRow(rowIdx) : row;
    }

    private Cell getCell(Sheet sheet, int row, int col) {
        Row r = getRow(sheet, row);
        Cell c = r.getCell(col);
        return (c == null) ? r.createCell(col) : c;
    }
}
