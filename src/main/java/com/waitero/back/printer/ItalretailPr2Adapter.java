package com.waitero.back.printer;

import com.waitero.back.entity.ModelloStampante;
import org.springframework.stereotype.Component;

@Component
public class ItalretailPr2Adapter implements PrinterAdapter {

    @Override
    public boolean supports(ModelloStampante modello) {
        return modello == ModelloStampante.ITALRETAIL_PR2;
    }

    @Override
    public void print(String contenuto) {
        // TODO stampa reale TCP/IP ITALRETAIL PR2.
        // TODO stampa ESC/POS.
        // TODO test connessione stampante.
        // TODO stampa di prova.
        // TODO gestione errori e retry di stampa.
    }
}
