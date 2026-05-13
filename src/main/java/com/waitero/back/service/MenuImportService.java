package com.waitero.back.service;

import com.waitero.back.dto.MenuImportResultDTO;
import com.waitero.back.dto.MenuImportRowResultDTO;
import com.waitero.back.dto.PiattoDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class MenuImportService {

    private final MenuService menuService;

    public MenuImportResultDTO importFromExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Seleziona un file Excel valido");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Il file Excel non contiene fogli leggibili");
            }

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("Nel file manca la riga di intestazione");
            }

            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> columns = readColumns(headerRow, formatter, evaluator);

            int totalRows = 0;
            int createdRows = 0;
            int updatedRows = 0;
            int failedRows = 0;
            List<MenuImportRowResultDTO> rowResults = new ArrayList<>();

            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isRowBlank(row, formatter, evaluator)) {
                    continue;
                }

                totalRows++;
                String nome = readText(row, columns, formatter, evaluator, "nome", "piatto", "dish");

                try {
                    PiattoDTO dto = parseRow(row, columns, formatter, evaluator);
                    boolean existed = menuService.existsDishForAuthenticatedRestaurant(dto.getNome());
                    menuService.upsertPiattoFromImport(dto, dto.getImageUrl());

                    if (existed) {
                        updatedRows++;
                    } else {
                        createdRows++;
                    }

                    rowResults.add(MenuImportRowResultDTO.builder()
                            .rowNumber(rowIndex + 1)
                            .nome(dto.getNome())
                            .status(existed ? "AGGIORNATO" : "CREATO")
                            .message("Importato con successo")
                            .build());
                } catch (Exception ex) {
                    failedRows++;
                    log.warn("Import menu fallito alla riga {}: {}", rowIndex + 1, ex.getMessage());
                    rowResults.add(MenuImportRowResultDTO.builder()
                            .rowNumber(rowIndex + 1)
                            .nome(nome)
                            .status("ERRORE")
                            .message(ex.getMessage())
                            .build());
                }
            }

            return MenuImportResultDTO.builder()
                    .totalRows(totalRows)
                    .createdRows(createdRows)
                    .updatedRows(updatedRows)
                    .failedRows(failedRows)
                    .rowResults(rowResults)
                    .build();
        } catch (IOException ex) {
            throw new RuntimeException("Errore durante la lettura del file Excel", ex);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Impossibile leggere il file Excel", ex);
        }
    }

    private PiattoDTO parseRow(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        String nome = readRequiredText(row, columns, formatter, evaluator, "nome", "piatto", "dish");
        String categoria = readRequiredText(row, columns, formatter, evaluator, "categoria", "categoria nome", "category");
        BigDecimal prezzo = readRequiredPrice(row, columns, formatter, evaluator, "prezzo", "price");

        PiattoDTO dto = new PiattoDTO();
        dto.setNome(nome);
        dto.setDescrizione(readText(row, columns, formatter, evaluator, "descrizione", "description"));
        dto.setIngredienti(readText(row, columns, formatter, evaluator, "ingredienti", "ingredients"));
        dto.setAllergeni(readText(row, columns, formatter, evaluator, "allergeni", "allergens"));
        dto.setImageUrl(readText(row, columns, formatter, evaluator, "immagine", "image", "image url"));
        dto.setConsigliato(readBoolean(row, columns, formatter, evaluator, "consigliato", "recommended", "top"));
        dto.setDisponibile(readBoolean(row, columns, formatter, evaluator, "disponibile", "available", "attivo"));
        dto.setPrezzo(prezzo);
        dto.setCategoria(categoria);
        return dto;
    }

    private Map<String, Integer> readColumns(Row headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            String key = normalizeHeader(formatter.formatCellValue(cell, evaluator));
            if (key != null && !key.isEmpty()) {
                columns.putIfAbsent(key, cell.getColumnIndex());
            }
        }
        return columns;
    }

    private String readRequiredText(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String... aliases
    ) {
        String value = readText(row, columns, formatter, evaluator, aliases);
        if (value == null) {
            throw new IllegalArgumentException("Campo obbligatorio mancante: " + aliases[0]);
        }
        return value;
    }

    private String readText(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String... aliases
    ) {
        Integer index = resolveColumn(columns, aliases);
        if (index == null) {
            return null;
        }

        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }

        String value = formatter.formatCellValue(cell, evaluator);
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Long readLong(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String... aliases
    ) {
        String value = readText(row, columns, formatter, evaluator, aliases);
        if (value == null) {
            return null;
        }
        String normalized = value.replace(",", ".").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(normalized).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("Valore non valido per " + aliases[0] + ": " + value);
        }
    }

    private BigDecimal readRequiredPrice(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String... aliases
    ) {
        String value = readRequiredText(row, columns, formatter, evaluator, aliases);
        String normalized = value.replace(",", ".").trim();
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Prezzo non valido: " + value);
        }
    }

    private Boolean readBoolean(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String... aliases
    ) {
        String value = readText(row, columns, formatter, evaluator, aliases);
        if (value == null) {
            return null;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("1", "true", "si", "sì", "yes", "y", "x").contains(normalized)) {
            return true;
        }
        if (List.of("0", "false", "no", "n").contains(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("Valore booleano non valido: " + value);
    }

    private Integer resolveColumn(Map<String, Integer> columns, String... aliases) {
        for (String alias : aliases) {
            String normalized = normalizeHeader(alias);
            Integer index = columns.get(normalized);
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private boolean isRowBlank(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Cell cell : row) {
            String value = formatter.formatCellValue(cell, evaluator);
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", "");
    }
}
