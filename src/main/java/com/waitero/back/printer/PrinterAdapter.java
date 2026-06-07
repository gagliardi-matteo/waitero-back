package com.waitero.back.printer;

import com.waitero.back.entity.ModelloStampante;

public interface PrinterAdapter {
    boolean supports(ModelloStampante modello);
    void print(String contenuto);
}
