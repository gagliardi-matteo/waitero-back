package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerOrderStateDTO {
    private OrdineDTO currentOrder;
    private CustomerDraftDTO draft;
}
