package com.comerzzia.ametller.pos.ncr.actions.sale.giftcard;

import java.math.BigDecimal;

import com.comerzzia.pos.persistence.giftcard.GiftCardBean;

public final class GiftCardPaymentContext {

        private final GiftCardBean giftCard;
        private final BigDecimal amount;
        private final Integer paymentId;

        public GiftCardPaymentContext(GiftCardBean giftCard, BigDecimal amount, Integer paymentId) {
                this.giftCard = giftCard;
                this.amount = amount;
                this.paymentId = paymentId;
        }

        public GiftCardBean getGiftCard() {
                return giftCard;
        }

        public BigDecimal getAmount() {
                return amount;
        }

        public Integer getPaymentId() {
                return paymentId;
        }
}
