package com.project.extension.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.extension.application.ports.PdfGenerator;
import com.project.extension.application.ports.PdfStorageService;
import com.project.extension.application.usecases.GerarPdfUseCase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public GerarPdfUseCase gerarPdfUseCase(@Qualifier("docxTemplateAdapter") PdfGenerator pdfGenerator, PdfStorageService storageService) {
        return new GerarPdfUseCase(pdfGenerator, storageService);
    }
}