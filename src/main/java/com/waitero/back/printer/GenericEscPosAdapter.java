package com.waitero.back.printer;

import com.waitero.back.entity.ModelloStampante;
import com.waitero.back.entity.Stampante;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GenericEscPosAdapter implements PrinterAdapter {

    private final TcpEscPosPrinterClient tcpClient;

    @Override
    public boolean supports(ModelloStampante modello) {
        return modello == ModelloStampante.GENERIC_ESC_POS
                || modello == ModelloStampante.EPSON_TM_T20
                || modello == ModelloStampante.EPSON_TM_M30
                || modello == ModelloStampante.CUSTOM_KUBE;
    }

    @Override
    public void print(Stampante stampante, String contenuto) {
        tcpClient.print(stampante, contenuto);
    }
}
