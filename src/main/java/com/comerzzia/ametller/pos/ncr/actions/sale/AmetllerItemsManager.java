package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.annotation.PostConstruct;

import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager.Descuento25Info;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.Totals;
import com.comerzzia.pos.services.ticket.cabecera.ITotalesTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;

@Lazy(false)
@Service
@Primary
@DependsOn("itemsManager")
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCRIPCION_DESCUENTO_25 = "Descuento del 25% aplicado";

    @Override
    protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
        ItemSold itemSold = super.lineaTicketToItemSold(linea);

        AmetllerScoTicketManager ametllerScoTicketManager = getAmetllerScoTicketManager();
        if (ametllerScoTicketManager == null) {
            return itemSold;
        }

        Descuento25Info info = ametllerScoTicketManager.getDescuento25Info(linea != null ? linea.getIdLinea() : null);
        if (info == null) {
            return itemSold;
        }

        BigDecimal precioConDto = info.getPrecioConDto();
        if (precioConDto != null) {
            itemSold.setFieldIntValue(ItemSold.Price, precioConDto);
        }

        BigDecimal importeConDto = info.getImporteConDto();
        if (importeConDto != null) {
            itemSold.setFieldIntValue(ItemSold.ExtendedPrice, importeConDto);
        }

        ItemSold discount = itemSold.getDiscountApplied();
        if (discount == null) {
            return itemSold;
        }

        BigDecimal descuentoCalculado = info.getImporteDescuento();
        prepararMensajeDescuento(linea, itemSold, info, descuentoCalculado, discount);

        return itemSold;
    }

    private void prepararMensajeDescuento(LineaTicket linea, ItemSold itemSold, Descuento25Info info,
            BigDecimal descuentoCalculado, ItemSold discount) {
        if (linea == null || info == null) {
            return;
        }

        String itemNumber = itemSold.getFieldValue(ItemSold.ItemNumber);
        discount.setFieldValue(ItemSold.ItemNumber, itemNumber);
        discount.setFieldValue(ItemSold.UPC, itemSold.getFieldValue(ItemSold.UPC));

        BigDecimal importeConDto = info.getImporteConDto();
        BigDecimal precioConDto = info.getPrecioConDto();

        if (descuentoCalculado == null || descuentoCalculado.compareTo(BigDecimal.ZERO) <= 0) {
            discount.setFieldValue(ItemSold.Price, "0");
            discount.setFieldValue(ItemSold.ExtendedPrice, "0");
            discount.setFieldValue(ItemSold.Description, itemSold.getFieldValue(ItemSold.Description));
            discount.setFieldValue(ItemSold.DiscountDescription, null);
            return;
        }

        if (precioConDto != null) {
            discount.setFieldIntValue(ItemSold.Price, precioConDto);
        }
        if (importeConDto != null) {
            discount.setFieldIntValue(ItemSold.ExtendedPrice, importeConDto);
        }

        StringBuilder descripcion = new StringBuilder();
        String descripcionBase = itemSold.getFieldValue(ItemSold.Description);
        if (descripcionBase != null) {
            descripcion.append(descripcionBase);
        }
        descripcion.append("\n").append(DESCRIPCION_DESCUENTO_25);
        descripcion.append(": -").append(descuentoCalculado.setScale(2, RoundingMode.HALF_UP));

        discount.setFieldValue(ItemSold.Description, descripcion.toString());
        discount.setFieldValue(ItemSold.DiscountDescription, DESCRIPCION_DESCUENTO_25);

        discount.setFieldValue(ItemSold.AssociatedItemNumber, null);
        discount.setFieldValue(ItemSold.DiscountAmount, null);
        discount.setFieldValue(ItemSold.RewardLocation, null);
        discount.setFieldValue(ItemSold.ShowRewardPoints, null);
    }

    @Override
    public void sendTotals() {
        AmetllerScoTicketManager ametllerScoTicketManager = getAmetllerScoTicketManager();
        if (ametllerScoTicketManager == null) {
            super.sendTotals();
            return;
        }

        if (ticketManager.getTicket() == null) {
            super.sendTotals();
            return;
        }

        ITotalesTicket totales = ticketManager.getTicket().getTotales();

        BigDecimal headerDiscounts = getHeaderDiscounts();
        BigDecimal totalAmount = totales.getTotalAPagar().subtract(headerDiscounts);
        BigDecimal entregado = totales.getEntregado();

        if (ticketManager.isTenderMode()) {
            entregado = entregado.subtract(headerDiscounts);
        }

        Totals totals = new Totals();
        totals.setFieldIntValue(Totals.TotalAmount, totalAmount);
        totals.setFieldIntValue(Totals.TaxAmount, totales.getImpuestos());

        if (totalAmount.compareTo(entregado) >= 0) {
            totals.setFieldIntValue(Totals.BalanceDue, totalAmount.subtract(entregado));
        } else {
            totals.setFieldIntValue(Totals.BalanceDue, BigDecimal.ZERO);
            totals.setFieldIntValue(Totals.ChangeDue, entregado.subtract(totalAmount));
        }

        totals.setFieldValue(Totals.ItemCount, String.valueOf(ticketManager.getTicket().getLineas().size()));

        BigDecimal discountAmount = escalaDosDecimales(totales.getTotalPromociones());
        discountAmount = discountAmount.add(ametllerScoTicketManager.getTotalDescuento25Aplicado());
        totals.setFieldIntValue(Totals.DiscountAmount, discountAmount);
        totals.setFieldValue(Totals.Points, String.valueOf(totales.getPuntos()));

        ncrController.sendMessage(totals);
    }

    private BigDecimal escalaDosDecimales(BigDecimal valor) {
        if (valor == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    private AmetllerScoTicketManager getAmetllerScoTicketManager() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            return (AmetllerScoTicketManager) ticketManager;
        }
        return null;
    }

    @Override
    @PostConstruct
    public void init() {
        super.init();
    }
}

