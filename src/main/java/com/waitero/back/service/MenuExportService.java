package com.waitero.back.service;

import com.waitero.back.dto.PiattoDTO;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class MenuExportService {

    private static final List<String> TEMPLATE_HEADERS = List.of(
            "Nome",
            "Categoria",
            "Categoria Codice",
            "Categoria Id",
            "Prezzo",
            "Descrizione",
            "Ingredienti",
            "Allergeni",
            "Consigliato",
            "Disponibile",
            "Immagine"
    );

    private final MenuService menuService;

    public byte[] buildTemplateWorkbook() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Template menù");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(TEMPLATE_HEADERS.get(i));
            }

            Row instructionRow = sheet.createRow(1);
            instructionRow.createCell(0).setCellValue("Compila almeno Nome, Categoria e Prezzo.");
            instructionRow.createCell(1).setCellValue("Puoi usare Categoria, Categoria Codice o Categoria Id.");
            instructionRow.createCell(2).setCellValue("Consigliato e Disponibile accettano si/no, true/false, 1/0.");

            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException("Impossibile generare il template Excel", ex);
        }
    }

    public byte[] buildCurrentMenuArchive() {
        List<PiattoDTO> dishes = menuService.toDTOList(menuService.getPiatti());
        byte[] workbookBytes = buildMenuWorkbook(dishes);
        byte[] pdfBytes = buildMenuPdf(dishes);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeZipEntry(zip, "menu.xlsx", workbookBytes);
            writeZipEntry(zip, "menu.pdf", pdfBytes);
            zip.finish();
            return output.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException("Impossibile generare l'archivio menu", ex);
        }
    }

    private byte[] buildMenuWorkbook(List<PiattoDTO> dishes) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Menu");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                headerRow.createCell(i).setCellValue(TEMPLATE_HEADERS.get(i));
            }

            int rowIndex = 1;
            for (PiattoDTO dish : dishes) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(safe(dish.getNome()));
                row.createCell(1).setCellValue(safe(dish.getCategoriaLabel() != null ? dish.getCategoriaLabel() : dish.getCategoria()));
                row.createCell(2).setCellValue(safe(dish.getCategoriaCode()));
                if (dish.getCategoriaId() != null) {
                    row.createCell(3).setCellValue(dish.getCategoriaId());
                }
                if (dish.getPrezzo() != null) {
                    row.createCell(4).setCellValue(dish.getPrezzo().doubleValue());
                }
                row.createCell(5).setCellValue(safe(dish.getDescrizione()));
                row.createCell(6).setCellValue(safe(dish.getIngredienti()));
                row.createCell(7).setCellValue(safe(dish.getAllergeni()));
                row.createCell(8).setCellValue(Boolean.TRUE.equals(dish.getConsigliato()) ? "SI" : "NO");
                row.createCell(9).setCellValue(Boolean.TRUE.equals(dish.getDisponibile()) ? "SI" : "NO");
                row.createCell(10).setCellValue(safe(dish.getImageUrl()));
            }

            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException("Impossibile generare il file Excel del menu", ex);
        }
    }

    private byte[] buildMenuPdf(List<PiattoDTO> dishes) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(document);
            writer.writeTitle("Menù Waitero");
            writer.writeParagraph("Esportato il " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            writer.writeParagraph("Totale piatti: " + dishes.size());
            writer.writeSpacer(8);

            Map<String, List<PiattoDTO>> grouped = groupByCategory(dishes);
            for (Map.Entry<String, List<PiattoDTO>> entry : grouped.entrySet()) {
                writer.writeSectionTitle(entry.getKey());
                for (PiattoDTO dish : entry.getValue()) {
                    writer.writeDish(dish);
                }
                writer.writeSpacer(6);
            }

            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException("Impossibile generare il PDF del menu", ex);
        }
    }

    private Map<String, List<PiattoDTO>> groupByCategory(List<PiattoDTO> dishes) {
        Map<String, List<PiattoDTO>> grouped = new LinkedHashMap<>();
        List<PiattoDTO> ordered = new ArrayList<>(dishes);
        ordered.sort(Comparator
                .comparing((PiattoDTO dish) -> safe(dish.getCategoriaLabel() != null ? dish.getCategoriaLabel() : dish.getCategoria()))
                .thenComparing(dish -> safe(dish.getNome())));

        for (PiattoDTO dish : ordered) {
            String category = safe(dish.getCategoriaLabel() != null ? dish.getCategoriaLabel() : dish.getCategoria());
            grouped.computeIfAbsent(category.isBlank() ? "Senza categoria" : category, key -> new ArrayList<>())
                    .add(dish);
        }
        return grouped;
    }

    private void writeZipEntry(ZipOutputStream zip, String fileName, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(fileName);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private final class PdfWriter {
        private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
        private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
        private static final float MARGIN_X = 42f;
        private static final float MARGIN_TOP = 48f;
        private static final float MARGIN_BOTTOM = 48f;
        private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2);

        private final PDDocument document;
        private PDPageContentStream contentStream;
        private PDPage page;
        private float y;

        private PdfWriter(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        private void newPage() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            contentStream = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN_TOP;
        }

        private void ensureSpace(float requiredHeight) throws IOException {
            if (y - requiredHeight < MARGIN_BOTTOM) {
                newPage();
            }
        }

        private void writeTitle(String text) throws IOException {
            ensureSpace(40);
            contentStream.beginText();
            contentStream.setFont(PDType1Font.HELVETICA_BOLD, 20);
            contentStream.newLineAtOffset(MARGIN_X, y);
            contentStream.showText(text);
            contentStream.endText();
            y -= 28;
        }

        private void writeSectionTitle(String text) throws IOException {
            ensureSpace(24);
            contentStream.beginText();
            contentStream.setFont(PDType1Font.HELVETICA_BOLD, 14);
            contentStream.newLineAtOffset(MARGIN_X, y);
            contentStream.showText(text);
            contentStream.endText();
            y -= 18;
        }

        private void writeParagraph(String text) throws IOException {
            List<String> lines = wrapText(text, PDType1Font.HELVETICA, 10);
            writeLines(lines, PDType1Font.HELVETICA, 10, 13);
        }

        private void writeSpacer(float amount) {
            y -= amount;
        }

        private void writeDish(PiattoDTO dish) throws IOException {
            List<String> lines = new ArrayList<>();
            lines.add("• " + safe(dish.getNome()) + " - " + formatPrice(dish.getPrezzo()));
            if (!safe(dish.getDescrizione()).isBlank()) {
                lines.addAll(wrapText("Descrizione: " + safe(dish.getDescrizione()), PDType1Font.HELVETICA, 9));
            }
            if (!safe(dish.getIngredienti()).isBlank()) {
                lines.addAll(wrapText("Ingredienti: " + safe(dish.getIngredienti()), PDType1Font.HELVETICA, 9));
            }
            if (!safe(dish.getAllergeni()).isBlank()) {
                lines.addAll(wrapText("Allergeni: " + safe(dish.getAllergeni()), PDType1Font.HELVETICA, 9));
            }
            lines.add("   Categoria: " + safe(dish.getCategoriaLabel() != null ? dish.getCategoriaLabel() : dish.getCategoria()));
            lines.add("   Consigliato: " + (Boolean.TRUE.equals(dish.getConsigliato()) ? "SI" : "NO"));

            writeLines(lines, PDType1Font.HELVETICA, 9, 12);
            y -= 4;
        }

        private void writeLines(List<String> lines, PDType1Font font, float fontSize, float lineHeight) throws IOException {
            for (String line : lines) {
                List<String> wrapped = wrapText(line, font, fontSize);
                ensureSpace(lineHeight * wrapped.size());
                for (String wrappedLine : wrapped) {
                    contentStream.beginText();
                    contentStream.setFont(font, fontSize);
                    contentStream.newLineAtOffset(MARGIN_X, y);
                    contentStream.showText(wrappedLine);
                    contentStream.endText();
                    y -= lineHeight;
                }
            }
        }

        private List<String> wrapText(String text, PDType1Font font, float fontSize) throws IOException {
            String normalized = safe(text);
            if (normalized.isBlank()) {
                return List.of("");
            }

            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : normalized.split("\\s+")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (font.getStringWidth(candidate) / 1000f * fontSize <= CONTENT_WIDTH) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    if (current.length() > 0) {
                        result.add(current.toString());
                    }
                    current.setLength(0);
                    current.append(word);
                }
            }
            if (current.length() > 0) {
                result.add(current.toString());
            }
            return result;
        }

        private String formatPrice(BigDecimal price) {
            if (price == null) {
                return "n/d";
            }
            return price.stripTrailingZeros().toPlainString() + " €";
        }
    }
}
