#pragma once
[["java:package:co.icesi"]]

module Chat {
    sequence<byte>   ByteSeq;
    sequence<string> StringSeq;

    // ---------- Excepciones de Dominio ----------
    exception ChatException {
        string reason;
    };

    exception UserAlreadyExistsException extends ChatException {};
    exception UserNotFoundException      extends ChatException {};
    exception RoomAlreadyExistsException extends ChatException {};
    exception RoomNotFoundException      extends ChatException {};
    exception NotInRoomException         extends ChatException {};
    exception CallException              extends ChatException {};
    exception TransferException          extends ChatException {};

    // ---------- Estructuras de Datos ----------
    struct PrivateMessage {
        string from;
        string to;
        string text;
        long   timestamp;
    };

    struct RoomMessage {
        string room;
        string from;
        string text;
        long   timestamp;
    };

    struct UdpEndpoint {
        string host;
        int    port;
    };

    struct FileChunk {
        string  transferId;
        string  fileName;
        long    totalSize;
        int     chunkIndex;
        int     totalChunks;
        string  sha256;
        ByteSeq data;
    };

    // ---------- Callback del Cliente ----------
    interface ChatCallback {
        void userConnected(string nickname);
        void userDisconnected(string nickname);
        void receivePrivateMessage(PrivateMessage msg);
        void receiveRoomMessage(RoomMessage msg);
        void receiveFileChunk(string from, string room, FileChunk chunk);

        // Senalizacion de voz 1-1
        void incomingCall(string from, UdpEndpoint audio);
        void callAccepted(string from, UdpEndpoint audio);
        void callRejected(string from);
        void callEnded(string from);

        // Senalizacion de voz grupal
        void roomCallStarted(string room, string by);
        void roomCallMemberJoined(string room, string nickname);
        void roomCallMemberLeft(string room, string nickname);

        // Monitor de salud
        void ping();
    };

    // ---------- Interfaz Remota del Servidor ----------
    interface ChatService {
        // Sesion y presencia
        void login(string nickname, ChatCallback* cb) throws UserAlreadyExistsException, ChatException;
        void logout(string nickname);
        idempotent StringSeq listUsers();

        // Mensajeria 1-1
        void sendPrivate(string from, string to, string text) throws UserNotFoundException, ChatException;

        // Salas de chat
        void createRoom(string room, string creator) throws RoomAlreadyExistsException, ChatException;
        idempotent StringSeq listRooms();
        void joinRoom(string room, string nickname) throws RoomNotFoundException, ChatException;
        void leaveRoom(string room, string nickname) throws RoomNotFoundException, NotInRoomException, ChatException;
        void sendRoomMessage(string from, string room, string text) throws RoomNotFoundException, NotInRoomException, ChatException;

        // Transferencia multimedia (Chunking)
        void sendFileChunkToUser(string from, string to, FileChunk chunk) throws UserNotFoundException, TransferException, ChatException;
        void sendFileChunkToRoom(string from, string room, FileChunk chunk) throws RoomNotFoundException, NotInRoomException, TransferException, ChatException;

        // Senalizacion de llamadas 1-1
        void startCall(string from, string to, UdpEndpoint myAudio) throws UserNotFoundException, CallException, ChatException;
        void acceptCall(string from, string caller, UdpEndpoint myAudio) throws CallException, ChatException;
        void rejectCall(string from, string caller) throws CallException, ChatException;
        void hangup(string from, string peer);

        // Senalizacion de llamadas grupales (Audio Relay)
        UdpEndpoint joinRoomCall(string room, string nickname, UdpEndpoint myAudio) throws RoomNotFoundException, NotInRoomException, CallException, ChatException;
        void leaveRoomCall(string room, string nickname);
    };
};
