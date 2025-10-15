package com.comerzzia.ametller.pos.ncr.actions.sale;

class GiftCardException extends Exception {

        private static final long serialVersionUID = 1L;

        GiftCardException(String message) {
                super(message);
        }

        GiftCardException(String message, Throwable cause) {
                super(message, cause);
        }
}
