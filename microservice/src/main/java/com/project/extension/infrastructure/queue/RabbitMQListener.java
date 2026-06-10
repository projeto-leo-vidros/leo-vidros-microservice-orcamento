package com.project.extension.infrastructure.queue;

import com.project.extension.application.usecases.GerarPdfUseCase;
import com.project.extension.domain.dto.OrcamentoDTO;
import com.project.extension.domain.dto.OrcamentoPdfProdResponseDTO;
import com.project.extension.domain.dto.OrcamentoPdfResponseDTO;
import com.project.extension.domain.exception.GeracaoPdfException;
import com.project.extension.infrastructure.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

public class RabbitMQListener {

    @Component
    @Profile("development")
    public static class Dev {
        private static final Logger log = LoggerFactory.getLogger(Dev.class);

        private final GerarPdfUseCase useCase;
        private final RabbitTemplate rabbitTemplate;

        public Dev(GerarPdfUseCase useCase, RabbitTemplate rabbitTemplate) {
            this.useCase = useCase;
            this.rabbitTemplate = rabbitTemplate;
        }

        @RabbitListener(queues = "fila.orcamento.pdf")
        public void receberMensagem(OrcamentoDTO payload) {
            String numero = payload.numeroOrcamento();
            Long id = payload.id();
            String cliente = payload.cliente() != null ? payload.cliente().nome() : "N/A";
            int qtdItens = payload.itens() != null ? payload.itens().size() : 0;

            log.info("[PDF] Solicitação recebida — numero={} id={} cliente='{}' itens={}",
                    numero, id, cliente, qtdItens);

            long inicio = System.currentTimeMillis();
            try {
                byte[] pdfBytes = useCase.executar(payload);
                long duracao = System.currentTimeMillis() - inicio;

                log.info("[PDF] Gerado com sucesso — numero={} id={} tamanho={}KB duracao={}ms",
                        numero, id, pdfBytes.length / 1024, duracao);

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.RESPONSE_FANOUT_EXCHANGE,
                        "",
                        new OrcamentoPdfResponseDTO(id, numero, pdfBytes)
                );

                log.info("[PDF] Resposta publicada na fila de retorno — numero={} id={}", numero, id);
            } catch (GeracaoPdfException e) {
                long duracao = System.currentTimeMillis() - inicio;
                log.error("[PDF] Falha na geração — numero={} id={} duracao={}ms motivo='{}'",
                        numero, id, duracao, e.getMessage(), e);
                throw e;
            }
        }
    }

    @Component
    @Profile("production")
    public static class Prod {
        private static final Logger log = LoggerFactory.getLogger(Prod.class);

        private final GerarPdfUseCase useCase;
        private final RabbitTemplate rabbitTemplate;

        public Prod(GerarPdfUseCase useCase, RabbitTemplate rabbitTemplate) {
            this.useCase = useCase;
            this.rabbitTemplate = rabbitTemplate;
        }

        @RabbitListener(queues = "fila.orcamento.pdf")
        public void receberMensagem(OrcamentoDTO payload) {
            String numero = payload.numeroOrcamento();
            Long id = payload.id();
            String cliente = payload.cliente() != null ? payload.cliente().nome() : "N/A";
            int qtdItens = payload.itens() != null ? payload.itens().size() : 0;

            log.info("[PDF] Solicitação recebida — numero={} id={} cliente='{}' itens={}",
                    numero, id, cliente, qtdItens);

            long inicio = System.currentTimeMillis();
            try {
                useCase.executar(payload);
                long duracao = System.currentTimeMillis() - inicio;

                log.info("[PDF] Gerado com sucesso — numero={} id={} duracao={}ms", numero, id, duracao);

                String nomeArquivo = "orcamento_" + numero + ".pdf";
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.RESPONSE_FANOUT_EXCHANGE,
                        "",
                        new OrcamentoPdfProdResponseDTO(id, numero, nomeArquivo)
                );

                log.info("[PDF] Resposta publicada na fila de retorno — numero={} id={}", numero, id);
            } catch (GeracaoPdfException e) {
                long duracao = System.currentTimeMillis() - inicio;
                log.error("[PDF] Falha na geração — numero={} id={} duracao={}ms motivo='{}'",
                        numero, id, duracao, e.getMessage(), e);
                throw e;
            }
        }
    }
}
