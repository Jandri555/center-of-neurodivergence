package com.example.hello;

import com.neurocenter.api.NeuroAppContext;
import com.neurocenter.api.NeuroPlatform;

/** Lógica neutral que pueden reutilizar las implementaciones Swing y Android. */
public final class HelloLogic {

    private HelloLogic() {}

    public static String message(NeuroAppContext context, int opens) {
        String platform = switch (context.platform()) {
            case DESKTOP -> "escritorio";
            case MOBILE -> "móvil";
        };
        return "¡Hola! Has abierto esta app " + opens
                + (opens == 1 ? " vez" : " veces")
                + " desde " + platform + ".";
    }
}
