package com.waitero.back.printer;

import com.waitero.back.entity.ModelloStampante;
import com.waitero.back.entity.Stampante;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ItalretailPr2Adapter implements PrinterAdapter {

    private final TcpEscPosPrinterClient tcpClient;

    @Override
    public boolean supports(ModelloStampante modello) {
        return modello == ModelloStampante.ITALRETAIL_PR2;
    }

    @Override
    public void print(Stampante stampante, String contenuto) {
        tcpClient.print(stampante, contenuto);
    }
}
