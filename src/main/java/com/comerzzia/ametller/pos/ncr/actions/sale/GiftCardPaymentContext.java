package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;

import com.comerzzia.pos.persistence.giftcard.GiftCardBean;

final class GiftCardPaymentContext {

        private final GiftCardBean giftCard;
        private final BigDecimal amount;
        private final Integer paymentId;

        GiftCardPaymentContext(GiftCardBean giftCard, BigDecimal amount, Integer paymentId) {
                this.giftCard = giftCard;
                this.amount = amount;
                this.paymentId = paymentId;
        }

        GiftCardBean getGiftCard() {
                return giftCard;
        }

        BigDecimal getAmount() {
                return amount;
        }

        Integer getPaymentId() {
                return paymentId;
        }
}
