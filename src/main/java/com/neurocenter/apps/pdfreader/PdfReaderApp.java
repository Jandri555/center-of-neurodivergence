package com.neurocenter.apps.pdfreader;

import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.AppContext;

import javax.swing.JComponent;

/** Lector de PDF con vista original y vista de lectura con Bionic Reading. */
public final class PdfReaderApp implements NeuroApp {

    private PdfReaderPanel panel;

    @Override public String id() { return "pdf-reader"; }

    @Override public String name() { return "Lector PDF"; }

    @Override public String description() {
        return "Lee PDFs con negrita al inicio de cada palabra (Bionic Reading) y ajusta fuente, espaciado y color.";
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
