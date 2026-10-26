package co.icesi.chat.client.ui;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Gestor sincronizado de impresion en consola para desacoplar eventos asincronos
 * del prompt de entrada del usuario en la CLI.
 */
public class ConsolePrinter {

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss");

    private String currentPrompt = "> ";

    public synchronized void setPrompt(String prompt) {
        this.currentPrompt = prompt;
    }

    public synchronized void printSystem(String message) {
        clearLine();
        System.out.println("\n[SISTEMA] " + message);
        reprintPrompt();
    }

    public synchronized void printPrivate(String from, String text) {
        clearLine();
        String time = TIME_FMT.format(new Date());
        System.out.println("\n[" + time + "] [DIRECTO de " + from + "] " + text);
        reprintPrompt();
    }

    public synchronized void printRoom(String room, String from, String text) {
        clearLine();
        String time = TIME_FMT.format(new Date());
        System.out.println("\n[" + time + "] [#" + room + "] " + from + ": " + text);
        reprintPrompt();
    }

    public synchronized void printFile(String message) {
        clearLine();
        System.out.println("\n[ARCHIVO] " + message);
        reprintPrompt();
    }

    public synchronized void printCall(String message) {
        clearLine();
        System.out.println("\n[LLAMADA] " + message);
        reprintPrompt();
    }

    public synchronized void printError(String error) {
        clearLine();
        System.out.println("\n[ERROR] " + error);
        reprintPrompt();
    }

    public synchronized void printRaw(String text) {
        clearLine();
        System.out.println(text);
        reprintPrompt();
    }

    private void clearLine() {
        System.out.print("\r\033[K");
    }

    private void reprintPrompt() {
        System.out.print(currentPrompt);
        System.out.flush();
    }
}
