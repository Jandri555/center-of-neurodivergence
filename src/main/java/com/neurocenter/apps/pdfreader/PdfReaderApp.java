package com.neurocenter.apps.pdfreader;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.AppContext;

import javax.swing.JComponent;

/** Lector de PDF con vista original y vista de lectura con Bionic Reading. */
public final class PdfReaderApp implements NeuroApp {

    private PdfReaderPanel panel;

    @Override public String id() { return "pdf-reader"; }

    @Override public String name() { return "Bionic PDF"; }

    @Override public String description() {
        return "Lector PDF con Bionic Reading, vista original y ajustes de lectura.";
    }

    @Override
    public JComponent createPanel(AppContext ctx) {
        panel = new PdfReaderPanel(ctx);
        return panel;
    }

    @Override
    public void stop() {
        if (panel != null) panel.dispose();
    }
}
