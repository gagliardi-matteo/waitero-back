package com.waitero.back.service;

import com.waitero.back.dto.MenuCategoryDTO;
import com.waitero.back.dto.PiattoDTO;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
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

    private static final List<String> TEMPLATE_IMPORT_HEADERS = List.of(
            "Nome",
            "Categoria",
            "Prezzo",
            "Descrizione",
            "Ingredienti",
            "Allergeni",
            "Consigliato",
            "Disponibile",
            "Immagine"
    );
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
    private static final int TEMPLATE_FIRST_DATA_ROW = 1;
    private static final int TEMPLATE_LAST_DATA_ROW = 500;

    private final MenuService menuService;
    private final MenuCategoryService menuCategoryService;

    public byte[] buildTemplateWorkbook() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Template menù");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_IMPORT_HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(TEMPLATE_IMPORT_HEADERS.get(i));
            }

            styleHeaderRow(workbook, headerRow);
            createCategoryValidation(workbook, sheet, menuCategoryService.getAuthenticatedCategories());
            Sheet instructionsSheet = workbook.createSheet("Istruzioni");
            instructionsSheet.createRow(0).createCell(0).setCellValue("Compila almeno Nome, Categoria e Prezzo.");
            instructionsSheet.createRow(1).createCell(0).setCellValue("Categoria usa una tendina con i valori ufficiali del locale.");
            instructionsSheet.createRow(2).createCell(0).setCellValue("Consigliato e Disponibile accettano si/no, true/false, 1/0.");
            instructionsSheet.createRow(3).createCell(0).setCellValue("Nel template non ci sono colonne tecniche di categoria: usa solo la tendina.");
            instructionsSheet.setColumnWidth(0, 100 * 256);

            for (int i = 0; i < TEMPLATE_IMPORT_HEADERS.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException("Impossibile generare il template Excel", ex);
        }
    }

    private void createCategoryValidation(XSSFWorkbook workbook, Sheet templateSheet, List<MenuCategoryDTO> categories) {
        Sheet hiddenSheet = workbook.createSheet("Categorie");
        int rowIndex = 0;
        for (MenuCategoryDTO category : categories) {
            hiddenSheet.createRow(rowIndex).createCell(0).setCellValue(safe(category.getLabel()));
            rowIndex++;
        }
        hiddenSheet.protectSheet("waitero-readonly");

        String lastCell = "$A$" + Math.max(categories.size(), 1);
        var categoryRangeName = workbook.createName();
        categoryRangeName.setNameName("waitero_categories");
        categoryRangeName.setRefersToFormula("'Categorie'!$A$1:" + lastCell);

        DataValidationHelper helper = templateSheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createFormulaListConstraint("waitero_categories");
        CellRangeAddressList addressList = new CellRangeAddressList(TEMPLATE_FIRST_DATA_ROW, TEMPLATE_LAST_DATA_ROW, 1, 1);
        DataValidation validation = helper.createValidation(constraint, addressList);
        validation.setShowErrorBox(true);
        validation.setSuppressDropDownArrow(false);
        validation.createErrorBox("Categoria non valida", "Seleziona una categoria dalla tendina del template.");
        templateSheet.addValidationData(validation);

        workbook.setSheetVisibility(workbook.getSheetIndex(hiddenSheet), SheetVisibility.VERY_HIDDEN);
    }

    private void styleHeaderRow(XSSFWorkbook workbook, Row headerRow) {
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        for (Cell cell : headerRow) {
            cell.setCellStyle(headerStyle);
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
