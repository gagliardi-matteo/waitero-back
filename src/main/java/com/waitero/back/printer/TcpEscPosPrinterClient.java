package com.waitero.back.printer;

import com.waitero.back.entity.Stampante;
import com.waitero.back.entity.TipoConnessione;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
public class TcpEscPosPrinterClient {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final Charset PRINTER_CHARSET = Charset.forName("IBM00858");

    public void print(Stampante stampante, String contenuto) {
        if (stampante.getTipoConnessione() != TipoConnessione.TCP_IP) {
            throw new RuntimeException("Stampa reale supportata solo per stampanti TCP/IP");
        }
        if (stampante.getIpAddress() == null || stampante.getIpAddress().isBlank() || stampante.getPorta() == null) {
            throw new RuntimeException("Endpoint TCP/IP stampante incompleto");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(stampante.getIpAddress().trim(), stampante.getPorta()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            output.write(buildEscPosPayload(contenuto));
            output.flush();
        } catch (IOException ex) {
            throw new RuntimeException("Impossibile stampare su " + stampante.getNome() + " (" + stampante.getIpAddress() + ":" + stampante.getPorta() + ")", ex);
        }
    }

    private byte[] buildEscPosPayload(String contenuto) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        buffer.write(new byte[]{0x1B, 0x40}); // init
        buffer.write(new byte[]{0x1B, 0x74, 0x13}); // CP858, when supported
        buffer.write(normalizeForPrinter(contenuto).getBytes(PRINTER_CHARSET));
        buffer.write('\n');
        buffer.write('\n');
        buffer.write('\n');
        buffer.write(new byte[]{0x1D, 0x56, 0x42, 0x00}); // partial cut
        return buffer.toByteArray();
    }

    private String normalizeForPrinter(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\u2019', '\'')
                .replace('\u2018', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u20AC', 'E');
    }
}
