package com.llmall.promotion.controller;

import com.llmall.common.advice.CommonExceptionAdvice;
import com.llmall.common.config.JsonConfig;
import com.llmall.promotion.domain.dto.VoucherPwDTO;
import com.llmall.promotion.domain.vo.VoucherPurchaseVO;
import com.llmall.promotion.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VoucherPurchaseControllerTest {
    private IVoucherService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(IVoucherService.class);
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new VoucherController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
                .setControllerAdvice(new CommonExceptionAdvice()).build();
    }
    @Test void paidRequestForwardsTheStableTokenAndKeepsLongIdAsString() throws Exception {
        when(service.receive(9L, "secret", "purchase-request-0001")).thenReturn(2114674488525242369L);
        mvc.perform(post("/vouchers/9/receive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pw\":\"secret\",\"requestId\":\"purchase-request-0001\"}"))
                .andExpect(status().isOk()).andExpect(content().string("\"2114674488525242369\""));
        verify(service).receive(9L, "secret", "purchase-request-0001");
    }
    @Test void freeLegacyRequestDoesNotRequireABody() throws Exception {
        when(service.receive(9L, null, null)).thenReturn(7L);
        mvc.perform(post("/vouchers/9/receive")).andExpect(status().isOk());
        verify(service).receive(9L, null, null);
    }
    @Test void purchaseRouteReturnsExplicitNotFoundShape() throws Exception {
        when(service.purchase("purchase-request-0001")).thenReturn(VoucherPurchaseVO.of("purchase-request-0001", null));
        mvc.perform(get("/vouchers/purchases/purchase-request-0001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_FOUND"))
                .andExpect(content().json("{\"requestId\":\"purchase-request-0001\",\"state\":\"NOT_FOUND\",\"voucherId\":null,\"amount\":null,\"userVoucherId\":null}"));
        verify(service).purchase("purchase-request-0001");
        verify(service, never()).getVoucherDetail(anyLong());
    }
    @Test void paymentPasswordCannotBeExposedByDtoToString() {
        VoucherPwDTO dto = new VoucherPwDTO(); dto.setPw("never-log-this-password"); dto.setRequestId("purchase-request-0001");
        assertFalse(dto.toString().contains("never-log-this-password"));
    }
}
