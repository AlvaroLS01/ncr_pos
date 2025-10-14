package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.Coupon;
import com.comerzzia.pos.ncr.messages.CouponException;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.VoidTransaction;
import com.comerzzia.pos.ncr.messages.VoidItem;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.i18n.I18N;
import com.comerzzia.pos.services.cupones.CuponAplicationException;

@Lazy(false)
@Service
@Primary
@DependsOn("itemsManager")
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCUENTO_25_DESCRIPTION = "Descuento del 25% aplicado";

    @Autowired
    @Lazy
    private AmetllerPayManager ametllerPayManager;

    @Override
    protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
        ItemSold itemSold = super.lineaTicketToItemSold(linea);

        if (linea != null && itemSold != null && ticketManager instanceof AmetllerScoTicketManager) {
            AmetllerScoTicketManager ametllerScoTicketManager = (AmetllerScoTicketManager) ticketManager;
            if (ametllerScoTicketManager.hasDescuento25Aplicado(linea)) {
                BigDecimal importeSinDto = linea.getImporteTotalSinDto();
                BigDecimal importeConDto = linea.getImporteTotalConDto();

                if (importeSinDto != null && importeConDto != null) {
                    BigDecimal ahorro = importeSinDto.subtract(importeConDto);

                    if (BigDecimalUtil.isMayorACero(ahorro)) {
                        itemSold.setDiscount(importeConDto, ahorro, DESCUENTO_25_DESCRIPTION);
                    } else {
                        ametllerScoTicketManager.removeDescuento25(linea.getIdLinea());
                    }
                }
            }
        }

        //Enviamos un unico ItemSold y un unico Totals para que no salga el problema del embolsado
        if (linea != null && itemSold != null) {
            BigDecimal importePromociones = linea.getImporteTotalPromociones();

            if (BigDecimalUtil.isMayorACero(importePromociones)) {
                BigDecimal precioConDto = linea.getPrecioTotalConDto();
                BigDecimal importeConDto = linea.getImporteTotalConDto();

                if (precioConDto != null) {
                    itemSold.setFieldIntValue(ItemSold.Price, precioConDto);
                }

                if (importeConDto != null) {
                    itemSold.setFieldIntValue(ItemSold.ExtendedPrice, importeConDto);
                }

                ItemSold discountApplied = itemSold.getDiscountApplied();

                if (discountApplied != null) {
                    String discountDescription = discountApplied.getFieldValue(ItemSold.Description);

                    if (StringUtils.isNotBlank(discountDescription)) {
                        itemSold.setFieldValue(ItemSold.Description, discountDescription);
                    }

                    discountApplied.setFieldIntValue(ItemSold.Price, BigDecimal.ZERO);
                    discountApplied.setFieldIntValue(ItemSold.ExtendedPrice, BigDecimal.ZERO);
                }
            }
        }

        return itemSold;
    }


    @Override
    protected void sendItemSold(final ItemSold itemSold) {
        if (itemSold == null) {
            return;
        }

        ncrController.sendMessage(itemSold);
        sendTotals();
    }


    @Override
    public void newItem(final LineaTicket newLine) {
        if (newLine == null) {
            return;
        }

        if (ticketManager != null && ticketManager.getTicket() != null) {
            ticketManager.getSesion().getSesionPromociones()
                    .aplicarPromociones((TicketVentaAbono) ticketManager.getTicket());
            ticketManager.getTicket().getTotales().recalcular();
        }

        ItemSold response = lineaTicketToItemSold(newLine);

        sendItemSold(response);

        linesCache.put(newLine.getIdLinea(), response);
    }

    @Override
    public void newTicket() {
        super.newTicket();

        if (ametllerPayManager != null) {
            ametllerPayManager.onTransactionStarted();
        }
    }

    @Override
    public void deleteAllItems(VoidTransaction message) {
        if (ametllerPayManager != null) {
            ametllerPayManager.onTransactionVoided();
        }

        super.deleteAllItems(message);
    }


    //Actualizamos linea si el articulo tiene promoción
    @Override
    @SuppressWarnings("unchecked")
    public void updateItems() {
        Set<String> couponsBeforeUpdate = null;

        if (ticketManager instanceof AmetllerScoTicketManager) {
            couponsBeforeUpdate = new HashSet<>();

            for (GlobalDiscountData discountData : globalDiscounts.values()) {
                if (discountData != null && discountData.couponMessage != null) {
                    couponsBeforeUpdate.add(discountData.couponMessage.getFieldValue(Coupon.UPC));
                }
            }
        }

        super.updateItems();

        if (ticketManager instanceof AmetllerScoTicketManager && couponsBeforeUpdate != null) {
            Set<String> couponsAfterUpdate = new HashSet<>();

            for (GlobalDiscountData discountData : globalDiscounts.values()) {
                if (discountData != null && discountData.couponMessage != null) {
                    couponsAfterUpdate.add(discountData.couponMessage.getFieldValue(Coupon.UPC));
                }
            }

            couponsBeforeUpdate.removeAll(couponsAfterUpdate);

            AmetllerScoTicketManager ametllerScoTicketManager = (AmetllerScoTicketManager) ticketManager;

            for (String removedCoupon : couponsBeforeUpdate) {
                if (StringUtils.isNotBlank(removedCoupon)) {
                    ametllerScoTicketManager.descartarCupon(removedCoupon);
                }
            }
        }

        if (ticketManager == null || ticketManager.getTicket() == null) {
            return;
        }

        for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
            ItemSold cachedItem = linesCache.get(ticketLine.getIdLinea());

            if (cachedItem == null) {
                continue;
            }

            ItemSold refreshedItem = lineaTicketToItemSold(ticketLine);

            String cachedPrice = cachedItem.getFieldValue(ItemSold.Price);
            String refreshedPrice = refreshedItem.getFieldValue(ItemSold.Price);
            String cachedExtendedPrice = cachedItem.getFieldValue(ItemSold.ExtendedPrice);
            String refreshedExtendedPrice = refreshedItem.getFieldValue(ItemSold.ExtendedPrice);
            String cachedDescription = cachedItem.getFieldValue(ItemSold.Description);
            String refreshedDescription = refreshedItem.getFieldValue(ItemSold.Description);

            if (!StringUtils.equals(cachedPrice, refreshedPrice)
                    || !StringUtils.equals(cachedExtendedPrice, refreshedExtendedPrice)
                    || !StringUtils.equals(cachedDescription, refreshedDescription)) {
                ncrController.sendMessage(refreshedItem);
                linesCache.put(ticketLine.getIdLinea(), refreshedItem);

                sendTotals();
            }
        }
    }
    
    @Override
    public boolean isCoupon(String code) {
        if (!(ticketManager instanceof AmetllerScoTicketManager)) {
            return super.isCoupon(code);
        }

        AmetllerScoTicketManager ametllerScoTicketManager = (AmetllerScoTicketManager) ticketManager;

        try {
            if (!ametllerScoTicketManager.registrarCupon(code)) {
                return false;
            }
        }
        catch (CuponAplicationException e) {
            sendCouponException(code, e.getMessage(), null);
            return true;
        }

        try {
            boolean applied = applyCoupon(code);

            if (!applied) {
                ametllerScoTicketManager.descartarCupon(code);
                sendCouponException(code, I18N.getTexto("No se ha podido aplicar el cupón."), null);
                return true;
            }
        }
        catch (CuponAplicationException e) {
            ametllerScoTicketManager.descartarCupon(code);
            sendCouponException(code, e.getMessage(), null);
            return true;
        }

        GlobalDiscountData couponData = globalDiscounts.get(GLOBAL_DISCOUNT_COUPON_PREFIX + code);

        if (couponData == null || couponData.couponMessage == null) {
            ametllerScoTicketManager.descartarCupon(code);
            sendCouponException(code, I18N.getTexto("No se ha podido aplicar el cupón."), null);
            return true;
        }

        ItemSold couponLine = createCouponItemSold(couponData.couponMessage);
        couponData.itemSoldMessage = couponLine;

        ametllerScoTicketManager.marcarCuponAplicado(code);

        sendCouponException(code, I18N.getTexto("Tu cupon ha sido leído correctamente"), I18N.getTexto("Cupon leído"));

        ncrController.sendMessage(couponLine);

        sendTotals();
        updateItems();

        return true;
    }

    private void sendCouponException(String code, String message, String title) {
        CouponException couponException = new CouponException();
        couponException.setFieldValue(CouponException.UPC, code);
        couponException.setFieldValue(CouponException.Message, message);
        couponException.setFieldValue(CouponException.ExceptionType, "0");
        couponException.setFieldValue(CouponException.ExceptionId, "0");
        if (StringUtils.isNotBlank(title)) {
            couponException.setFieldValue(CouponException.Title, title);
        }
        ncrController.sendMessage(couponException);
    }

    private ItemSold createCouponItemSold(Coupon couponMessage) {
        ItemSold couponLine = new ItemSold();
        couponLine.setFieldValue(ItemSold.UPC, couponMessage.getFieldValue(Coupon.UPC));
        couponLine.setFieldValue(ItemSold.Description, couponMessage.getFieldValue(Coupon.Description));
        couponLine.setFieldValue(ItemSold.ItemNumber, couponMessage.getFieldValue(Coupon.ItemNumber));
        couponLine.setFieldIntValue(ItemSold.Price, BigDecimal.ZERO);
        couponLine.setFieldIntValue(ItemSold.ExtendedPrice, BigDecimal.ZERO);
        couponLine.setFieldValue(ItemSold.RequiresSecurityBagging, "5");
        return couponLine;
    }

    @Override
    public void deleteItem(VoidItem message) {
        if (message != null && ticketManager instanceof AmetllerScoTicketManager) {
            String itemNumberField = message.getFieldValue(VoidItem.ItemNumber);

            if (StringUtils.isNotBlank(itemNumberField)) {
                Integer itemNumber = Integer.valueOf(itemNumberField);

                if (itemNumber >= GLOBAL_DISCOUNT_FIRST_ITEM_ID) {
                    ((AmetllerScoTicketManager) ticketManager).descartarCupon(message.getFieldValue(VoidItem.UPC));
                }
            }
        }

        super.deleteItem(message);
    }
}
