package com.neurocenter.programs.pdfreader;

import com.neurocenter.api.NeuroProgram;
import com.neurocenter.api.ProgramContext;

import javax.swing.JComponent;

/** Lector de PDF con vista original y vista de lectura con Bionic Reading. */
public final class PdfReaderProgram implements NeuroProgram {

    private PdfReaderPanel panel;

    @Override public String id() { return "pdf-reader"; }

    @Override public String name() { return "Lector PDF"; }

    @Override public String description() {
        return "Lee PDFs con negrita al inicio de cada palabra (Bionic Reading) y ajusta fuente, espaciado y color.";
    }

    @Override
    public JComponent createPanel(ProgramContext ctx) {
        panel = new PdfReaderPanel(ctx);
        return panel;
    }

    @Override
    public void stop() {
        if (panel != null) panel.dispose();
    }
}
