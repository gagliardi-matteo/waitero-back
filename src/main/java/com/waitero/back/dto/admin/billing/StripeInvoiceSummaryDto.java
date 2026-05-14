package com.waitero.back.dto.admin.billing;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class StripeInvoiceSummaryDto {
    private String invoiceId;
    private String status;
    private String collectionMethod;
    private String hostedInvoiceUrl;
    private String invoicePdf;
    private BigDecimal amountDue;
    private BigDecimal amountPaid;
    private boolean autoAdvance;
}
