package co.icesi.chat.client.ui;

import co.icesi.Chat.ChatCallbackPrx;
import co.icesi.Chat.ChatServicePrx;
import co.icesi.chat.client.audio.VoiceCallManager;
import co.icesi.chat.client.file.FileSender;
import co.icesi.chat.client.util.ErrorMessages;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * Interfaz de Linea de Comandos (CLI) interactiva.
 * El hilo lector de System.in esta completamente desacoplado de la impresion de eventos asincronos.
 */
public class CommandLineUI {

    private final ChatServicePrx chatService;
    private final ChatCallbackPrx callbackPrx;
    private final ConsolePrinter printer;
    private final FileSender fileSender;
    private final VoiceCallManager voiceCallManager;

    private String currentNickname;
    private volatile boolean running = true;

    public CommandLineUI(ChatServicePrx chatService, ChatCallbackPrx callbackPrx,
                         ConsolePrinter printer, FileSender fileSender,
                         VoiceCallManager voiceCallManager) {
        this.chatService = chatService;
        this.callbackPrx = callbackPrx;
        this.printer = printer;
        this.fileSender = fileSender;
        this.voiceCallManager = voiceCallManager;
    }

    public void start() {
        printBanner();
        printer.printSystem("Bienvenido a Chat Ice 3.7. Usa /login <nickname> para comenzar o /help para ver comandos.");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (running) {
                String prompt = currentNickname != null ? ("[" + currentNickname + "] > ") : "> ";
                printer.setPrompt(prompt);
                System.out.print(prompt);
                System.out.flush();

                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                handleInput(line);
            }
        } catch (Exception e) {
            printer.printError("Error en lectura de consola: " + ErrorMessages.describe(e));
        }
    }

    private void handleInput(String input) {
        if (!input.startsWith("/")) {
            printer.printError("Comando desconocido. Los comandos inician con '/'. Escribe /help para ayuda.");
            return;
        }

        String[] parts = input.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        try {
            switch (cmd) {
                case "/help":
                    printHelp();
                    break;

                case "/login":
                    if (parts.length < 2) {
                        printer.printError("Uso: /login <nickname>");
                        return;
                    }
                    if (currentNickname != null) {
                        printer.printError("Ya has iniciado sesion como '" + currentNickname + "'. Usa /exit para desconectar.");
                        return;
                    }
                    String nick = parts[1].trim();
                    chatService.login(nick, callbackPrx);
                    currentNickname = nick;
                    voiceCallManager.setMyNickname(nick);
                    printer.printSystem("¡Sesion iniciada exitosamente como " + currentNickname + "!");
                    break;

                case "/users":
                    ensureLoggedIn();
                    String[] users = chatService.listUsers();
                    printer.printSystem("Usuarios activos (" + users.length + "): " + Arrays.toString(users));
                    break;

                case "/msg":
                    ensureLoggedIn();
                    if (parts.length < 3) {
                        printer.printError("Uso: /msg <usuario> <mensaje>");
                        return;
                    }
                    chatService.sendPrivate(currentNickname, parts[1], parts[2]);
                    printer.printPrivate("Tu -> " + parts[1], parts[2]);
                    break;

                case "/create":
                    ensureLoggedIn();
                    if (parts.length < 2) {
                        printer.printError("Uso: /create <nombre_sala>");
                        return;
                    }
                    chatService.createRoom(parts[1], currentNickname);
                    printer.printSystem("Sala creada: " + parts[1]);
                    break;

                case "/rooms":
                    ensureLoggedIn();
                    String[] rooms = chatService.listRooms();
                    printer.printSystem("Salas disponibles (" + rooms.length + "): " + Arrays.toString(rooms));
                    break;

                case "/join":
                    ensureLoggedIn();
                    if (parts.length < 2) {
                        printer.printError("Uso: /join <nombre_sala>");
                        return;
                    }
                    chatService.joinRoom(parts[1], currentNickname);
                    printer.printSystem("Te has unido a la sala #" + parts[1]);
                    break;

                case "/leave":
                    ensureLoggedIn();
                    if (parts.length < 2) {
                        printer.printError("Uso: /leave <nombre_sala>");
                        return;
                    }
                    chatService.leaveRoom(parts[1], currentNickname);
                    printer.printSystem("Has abandonado la sala #" + parts[1]);
                    break;

                case "/r":
                case "/room":
                    ensureLoggedIn();
                    if (parts.length < 3) {
                        printer.printError("Uso: /room <nombre_sala> <mensaje>");
                        return;
                    }
                    chatService.sendRoomMessage(currentNickname, parts[1], parts[2]);
                    printer.printRoom(parts[1], "Tu", parts[2]);
                    break;

                case "/sendfile":
                    ensureLoggedIn();
                    if (parts.length < 3) {
                        printer.printError("Uso: /sendfile <usuario|#sala> <ruta_archivo>");
                        return;
                    }
                    String dest = parts[1];
                    String path = parts[2];
                    boolean isRoom = dest.startsWith("#");
                    String actualDest = isRoom ? dest.substring(1) : dest;
                    fileSender.sendFileAsync(currentNickname, actualDest, path, isRoom);
                    break;

                case "/call":
                    ensureLoggedIn();
                    if (parts.length < 2) {
                        printer.printError("Uso: /call <usuario>");
                        return;
                    }
                    voiceCallManager.startDirectCall(parts[1]);
                    break;

                case "/accept":
                    ensureLoggedIn();
                    voiceCallManager.acceptCall();
                    break;

                case "/reject":
                    ensureLoggedIn();
                    voiceCallManager.rejectCall();
                    break;

                case "/hangup":
                    ensureLoggedIn();
                    voiceCallManager.hangup();
                    break;

                case "/groupcall":
                    ensureLoggedIn();
                    if (parts.length < 2) {
                        printer.printError("Uso: /groupcall <nombre_sala>");
                        return;
                    }
                    voiceCallManager.joinRoomCall(parts[1]);
                    break;

                case "/leavecall":
                    ensureLoggedIn();
                    voiceCallManager.leaveCall();
                    break;

                case "/mute":
                    ensureLoggedIn();
                    voiceCallManager.setMuted(true);
                    break;

                case "/unmute":
                    ensureLoggedIn();
                    voiceCallManager.setMuted(false);
                    break;

                case "/exit":
                    if (currentNickname != null) {
                        try {
                            voiceCallManager.hangup();
                            chatService.logout(currentNickname);
                        } catch (Exception ignored) {
                        }
                    }
                    running = false;
                    printer.printSystem("Sesion finalizada. ¡Hasta luego!");
                    System.exit(0);
                    break;

                default:
                    printer.printError("Comando no reconocido: " + cmd + ". Escribe /help para lista de comandos.");
            }
        } catch (Exception e) {
            printer.printError("Fallo al ejecutar " + cmd + ": " + ErrorMessages.describe(e));
        }
    }

    private void ensureLoggedIn() throws Exception {
        if (currentNickname == null) {
            throw new IllegalStateException("Debes iniciar sesion primero con /login <nickname>");
        }
    }

    private void printBanner() {
        System.out.println("===================================================================");
        System.out.println("   PLATAFORMA DISTRIBUIDA DE CHAT Y VOZ MULTIMEDIA (ZERO-C ICE)   ");
        System.out.println("         Computacion en Internet I - Universidad Icesi             ");
        System.out.println("===================================================================");
    }

    private void printHelp() {
        System.out.println("\n--- COMANDOS DISPONIBLES ---");
        System.out.println("  /login <nick>               - Iniciar sesion con un nickname unico");
        System.out.println("  /users                      - Listar usuarios conectados");
        System.out.println("  /msg <user> <texto>         - Enviar mensaje privado 1 a 1");
        System.out.println("  /create <sala>              - Crear nueva sala grupal");
        System.out.println("  /rooms                      - Listar salas disponibles");
        System.out.println("  /join <sala>                - Unirse a una sala");
        System.out.println("  /leave <sala>               - Salir de una sala");
        System.out.println("  /room <sala> <texto>        - Enviar mensaje a una sala grupal");
        System.out.println("  /sendfile <user|#sala> <path> - Enviar archivo multimedia (imagenes, pdf, wav)");
        System.out.println("  /call <user>                - Iniciar llamada de voz 1 a 1 (UDP)");
        System.out.println("  /accept                     - Aceptar llamada entrante");
        System.out.println("  /reject                     - Rechazar llamada entrante");
        System.out.println("  /hangup                     - Colgar llamada actual");
        System.out.println("  /groupcall <sala>           - Unirse a conferencia de voz grupal (AudioRelay)");
        System.out.println("  /leavecall                  - Salir de la llamada actual (1 a 1 o grupal)");
        System.out.println("  /mute                       - Silenciar microfono");
        System.out.println("  /unmute                     - Activar microfono");
        System.out.println("  /help                       - Mostrar esta ayuda");
        System.out.println("  /exit                       - Desconectar y salir\n");
    }
}
