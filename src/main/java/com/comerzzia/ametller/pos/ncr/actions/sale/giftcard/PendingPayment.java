package com.comerzzia.ametller.pos.ncr.actions.sale.giftcard;

import java.math.BigDecimal;

import com.comerzzia.pos.ncr.messages.Tender;

public final class PendingPayment {

        private final Tender message;
        private final GiftCardContext context;
        private final String cardNumber;
        private final BigDecimal amount;

        public PendingPayment(Tender message, GiftCardContext context, String cardNumber, BigDecimal amount) {
                this.message = message;
                this.context = context;
                this.cardNumber = cardNumber;
                this.amount = amount;
        }

        public Tender getMessage() {
                return message;
        }

        public GiftCardContext getContext() {
                return context;
        }

        public String getCardNumber() {
                return cardNumber;
        }

        public BigDecimal getAmount() {
                return amount;
        }
}
