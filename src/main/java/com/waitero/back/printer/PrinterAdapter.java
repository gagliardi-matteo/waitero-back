package com.waitero.back.printer;

import com.waitero.back.entity.ModelloStampante;
import com.waitero.back.entity.Stampante;

public interface PrinterAdapter {
    boolean supports(ModelloStampante modello);
    void print(Stampante stampante, String contenuto);
}
