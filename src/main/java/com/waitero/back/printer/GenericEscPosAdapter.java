package com.waitero.back.printer;

import com.waitero.back.entity.ModelloStampante;
import org.springframework.stereotype.Component;

@Component
public class GenericEscPosAdapter implements PrinterAdapter {

    @Override
    public boolean supports(ModelloStampante modello) {
        return modello == ModelloStampante.GENERIC_ESC_POS
                || modello == ModelloStampante.EPSON_TM_T20
                || modello == ModelloStampante.EPSON_TM_M30
                || modello == ModelloStampante.CUSTOM_KUBE;
    }

    @Override
    public void print(String contenuto) {
        // TODO stampa reale TCP/IP.
        // TODO stampa ESC/POS.
        // TODO stampa Bluetooth.
        // TODO gestione piu stampanti contemporaneamente.
        // TODO routing per reparto: cucina, bar, pizzeria.
        // TODO associazione categorie piatti -> stampanti.
        // TODO gestione errori e retry di stampa.
    }
}
