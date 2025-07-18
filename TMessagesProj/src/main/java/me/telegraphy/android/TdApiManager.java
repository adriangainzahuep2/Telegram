package me.telegraphy.android;

import org.drinkless.td.libcore.telegram.Client;
import org.drinkless.td.libcore.telegram.TdApi;

import me.telegraphy.android.tdlibnative.ClientTdLib;

import me.telegraphy.android.messenger.AndroidUtilities;
import me.telegraphy.android.messenger.ApplicationLoader;
import me.telegraphy.android.messenger.FileLog;
import me.telegraphy.android.messenger.UserConfig;
import me.telegraphy.android.messenger.NotificationCenter;
import me.telegraphy.android.messenger.LocaleController;
import me.telegraphy.android.messenger.BuildVars;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import android.content.Context;

/**
 * Gestor principal para la API de TDLib.
 * Maneja toda la comunicación con TDLib y coordina las operaciones del cliente Telegram.
 */
public class TdApiManager {

    private static final String TAG = "TdApiManager";

    // Instancia singleton
    private static volatile TdApiManager instance = null;
    private static DatabaseManager databaseManager;
    private static Gson gson;

    // Cliente TDLib
    private static volatile Client client = null;

    private static volatile ClientTdLib clientTdLib = null;

    // Estado de autorización
    private static volatile TdApi.AuthorizationState authorizationState = null;
    private static volatile boolean haveAuthorization = false;
    private static volatile boolean isClientDestroyed = false;

    // Contadores y mapas de seguimiento
    private static final AtomicLong currentQueryId = new AtomicLong();
    private static final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<TdApi.Object>> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TdApi.Chat> chats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TdApi.User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TdApi.BasicGroup> basicGroups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TdApi.Supergroup> supergroups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TdApi.SecretChat> secretChats = new ConcurrentHashMap<>();

    // Ejecutor para tareas en segundo plano
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

    // Callbacks para diferentes operaciones
    private AuthorizationStateListener authorizationStateListener;
    private MessageListener messageListener;
    private ChatListener chatListener;
    private CallListener callListener;
    private FileDownloadListener fileDownloadListener;

    // Constructor privado para singleton
    private TdApiManager() {
        Context context = ApplicationLoader.applicationContext;
        databaseManager = DatabaseManager.getInstance(context);
        gson = new Gson();
        initializeClient();
    }

    /**
     * Obtiene la instancia singleton de TdApiManager.
     */
    public static TdApiManager getInstance() {
        if (instance == null) {
            synchronized (TdApiManager.class) {
                if (instance == null) {
                    instance = new TdApiManager();
                }
            }
        }
        return instance;
    }

    /**
     * Inicializa el cliente TDLib.
     */
    private void initializeClient() {
        if (client == null) {
            synchronized (TdApiManager.class) {
                if (client == null) {
                    try {
                        Client.setLogVerbosityLevel(BuildVars.DEBUG_VERSION ? 2 : 1);
                        client = Client.create(new UpdatesHandler(), null, null);
                        isClientDestroyed = false;
                        FileLog.d(TAG + ": TDLib client created successfully.");
                    } catch (Exception e) {
                        FileLog.e(TAG + ": Error creating TDLib client", e);
                        throw new RuntimeException("Failed to create TDLib client", e);
                    }
                }
            }
        }
    }

    /**
     * Obtiene el cliente TDLib.
     */
    public Client getClient() {
        if (client == null || isClientDestroyed) {
            initializeClient();
        }
        return client;
    }

    private static void onAuthorizationStateUpdated(TdApi.AuthorizationState newAuthorizationState) {
        if (newAuthorizationState != null) {
            authorizationState = newAuthorizationState;
        }
        switch (authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateWaitTdlibParameters");
                TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
                // Estos parámetros deben obtenerse de AndroidUtilities y de la configuración de la app
                parameters.databaseDirectory = new File(ApplicationLoader.getFilesDirFixed(), "telegraphy_tdlib_db_" + UserConfig.selectedAccount).getAbsolutePath();
                parameters.useMessageDatabase = true;
                parameters.useSecretChats = true;
                parameters.apiId = BuildVars.APP_ID; // Usar el api_id real
                parameters.apiHash = BuildVars.APP_HASH; // Usar el api_hash real
                parameters.systemLanguageCode = LocaleController.getInstance().getSystemLocaleStringIso639();
                parameters.deviceModel = AndroidUtilities.BuildVars.MODEL;
                parameters.systemVersion = AndroidUtilities.BuildVars.SDK_INT_STRING;
                parameters.applicationVersion = BuildVars.APP_VERSION;
                parameters.enableStorageOptimizer = true;
                parameters.useFileDatabase = true;
                parameters.useChatInfoDatabase = true;
                parameters.filesDirectory = new File(ApplicationLoader.getFilesDirFixed(), "telegraphy_tdlib_files_" + UserConfig.selectedAccount).getAbsolutePath();

                getClient().send(new TdApi.SetTdlibParameters(parameters), defaultHandler);
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateWaitEncryptionKey");
                getClient().send(new TdApi.CheckDatabaseEncryptionKey(), defaultHandler); // Usar una clave si está configurada
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateWaitPhoneNumber - Waiting for phone number.");
                // Aquí la UI debería solicitar el número de teléfono y luego llamar a setAuthenticationPhoneNumber
                haveAuthorization = false;
                break;
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateWaitCode - Waiting for auth code.");
                // La UI debería solicitar el código
                haveAuthorization = false;
                break;
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateWaitPassword - Waiting for 2FA password.");
                // La UI debería solicitar la contraseña de 2FA
                haveAuthorization = false;
                break;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateReady - Logged in successfully.");
                haveAuthorization = true;
                // Aquí se pueden realizar acciones post-login, como cargar chats
                getChats(new TdApi.ChatListMain(), 50);
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateLoggingOut");
                haveAuthorization = false;
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateClosing");
                haveAuthorization = false;
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                FileLog.d(TAG + ": AuthorizationStateClosed - Client closed.");
                client = null; // Forzar la recreación del cliente en el próximo getClient()
                haveAuthorization = false;
                break;
            default:
                FileLog.e(TAG + ": Unhandled authorization state: " + authorizationState);
        }
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.tdLibAuthorizationStateUpdated, authorizationState);
    }

    /**
     * Manejador principal de actualizaciones de TDLib.
     */
    private class UpdatesHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            try {
                switch (object.getConstructor()) {
                    case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                        handleAuthorizationStateUpdate((TdApi.UpdateAuthorizationState) object);
                        break;
                    case TdApi.UpdateNewMessage.CONSTRUCTOR:
                        handleNewMessage((TdApi.UpdateNewMessage) object);
                        break;
                    case TdApi.UpdateNewChat.CONSTRUCTOR:
                        handleNewChat((TdApi.UpdateNewChat) object);
                        break;
                    case TdApi.UpdateChatTitle.CONSTRUCTOR:
                        handleChatTitle((TdApi.UpdateChatTitle) object);
                        break;
                    case TdApi.UpdateChatPhoto.CONSTRUCTOR:
                        handleChatPhoto((TdApi.UpdateChatPhoto) object);
                        break;
                    case TdApi.UpdateChatLastMessage.CONSTRUCTOR:
                        handleChatLastMessage((TdApi.UpdateChatLastMessage) object);
                        break;
                    case TdApi.UpdateChatPosition.CONSTRUCTOR:
                        handleChatPosition((TdApi.UpdateChatPosition) object);
                        break;
                    case TdApi.UpdateChatReadInbox.CONSTRUCTOR:
                        handleChatReadInbox((TdApi.UpdateChatReadInbox) object);
                        break;
                    case TdApi.UpdateChatReadOutbox.CONSTRUCTOR:
                        handleChatReadOutbox((TdApi.UpdateChatReadOutbox) object);
                        break;
                    case TdApi.UpdateUser.CONSTRUCTOR:
                        handleUserUpdate((TdApi.UpdateUser) object);
                        break;
                    case TdApi.UpdateBasicGroup.CONSTRUCTOR:
                        handleBasicGroupUpdate((TdApi.UpdateBasicGroup) object);
                        break;
                    case TdApi.UpdateSupergroup.CONSTRUCTOR:
                        handleSupergroupUpdate((TdApi.UpdateSupergroup) object);
                        break;
                    case TdApi.UpdateSecretChat.CONSTRUCTOR:
                        handleSecretChatUpdate((TdApi.UpdateSecretChat) object);
                        break;
                    case TdApi.UpdateCall.CONSTRUCTOR:
                        handleCallUpdate((TdApi.UpdateCall) object);
                        break;
                    case TdApi.UpdateFile.CONSTRUCTOR:
                        handleFileUpdate((TdApi.UpdateFile) object);
                        break;
                    case TdApi.UpdateFileDownload.CONSTRUCTOR:
                        handleFileDownloadUpdate((TdApi.UpdateFileDownload) object);
                        break;
                    case TdApi.UpdateConnectionState.CONSTRUCTOR:
                        handleConnectionStateUpdate((TdApi.UpdateConnectionState) object);
                        break;
                    default:
                        FileLog.d(TAG + ": Unhandled update: " + object.getClass().getSimpleName());
                }

                // Notificar a través del NotificationCenter
                NotificationCenter.getInstance(UserConfig.selectedAccount)
                    .postNotificationName(NotificationCenter.tdLibUpdate, object);

            } catch (Exception e) {
                FileLog.e(TAG + ": Error handling update", e);
            }
        }
    }

    /**
     * Maneja actualizaciones de estado de autorización.
     */
    private void handleAuthorizationStateUpdate(TdApi.UpdateAuthorizationState update) {
        authorizationState = update.authorizationState;

        switch (authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                handleWaitTdlibParameters();
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                handleWaitEncryptionKey();
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
                handleWaitPhoneNumber();
                break;
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR:
                handleWaitCode((TdApi.AuthorizationStateWaitCode) authorizationState);
                break;
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR:
                handleWaitPassword((TdApi.AuthorizationStateWaitPassword) authorizationState);
                break;
            case TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR:
                handleWaitRegistration((TdApi.AuthorizationStateWaitRegistration) authorizationState);
                break;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                handleReady();
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                handleLoggingOut();
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                handleClosing();
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                handleClosed();
                break;
            case TdApi.UpdateNotification.CONSTRUCTOR:
                handleNotification((TdApi.UpdateNotification) update);
                break;

            case TdApi.UpdateNotificationGroup.CONSTRUCTOR:
                handleNotificationGroup((TdApi.UpdateNotificationGroup) update);
                break;

            case TdApi.UpdateActiveNotifications.CONSTRUCTOR:
                handleActiveNotifications((TdApi.UpdateActiveNotifications) update);
                break;

            case TdApi.UpdateHavePendingNotifications.CONSTRUCTOR:
                handleHavePendingNotifications((TdApi.UpdateHavePendingNotifications) update);
                break;

            case TdApi.UpdateChatDraftMessage.CONSTRUCTOR:
                handleChatDraftMessage((TdApi.UpdateChatDraftMessage) update);
                break;

            case TdApi.UpdateChatFilters.CONSTRUCTOR:
                handleChatFilters((TdApi.UpdateChatFilters) update);
                break;

            case TdApi.UpdateUnreadMessageCount.CONSTRUCTOR:
                handleUnreadMessageCount((TdApi.UpdateUnreadMessageCount) update);
                break;

            case TdApi.UpdateUnreadChatCount.CONSTRUCTOR:
                handleUnreadChatCount((TdApi.UpdateUnreadChatCount) update);
                break;

            case TdApi.UpdateOption.CONSTRUCTOR:
                handleOption((TdApi.UpdateOption) update);
                break;

            case TdApi.UpdateInstalledStickerSets.CONSTRUCTOR:
                handleInstalledStickerSets((TdApi.UpdateInstalledStickerSets) update);
                break;

            case TdApi.UpdateTrendingStickerSets.CONSTRUCTOR:
                handleTrendingStickerSets((TdApi.UpdateTrendingStickerSets) update);
                break;

            case TdApi.UpdateRecentStickers.CONSTRUCTOR:
                handleRecentStickers((TdApi.UpdateRecentStickers) update);
                break;

            case TdApi.UpdateFavoriteStickers.CONSTRUCTOR:
                handleFavoriteStickers((TdApi.UpdateFavoriteStickers) update);
                break;

            case TdApi.UpdateSavedAnimations.CONSTRUCTOR:
                handleSavedAnimations((TdApi.UpdateSavedAnimations) update);
                break;

            case TdApi.UpdateSelectedBackground.CONSTRUCTOR:
                handleSelectedBackground((TdApi.UpdateSelectedBackground) update);
                break;

            case TdApi.UpdateChatTheme.CONSTRUCTOR:
                handleChatTheme((TdApi.UpdateChatTheme) update);
                break;

            case TdApi.UpdateLanguagePackStrings.CONSTRUCTOR:
                handleLanguagePackStrings((TdApi.UpdateLanguagePackStrings) update);
                break;

            case TdApi.UpdateConnectionState.CONSTRUCTOR:
                handleConnectionState((TdApi.UpdateConnectionState) update);
                break;

            case TdApi.UpdateTermsOfService.CONSTRUCTOR:
                handleTermsOfService((TdApi.UpdateTermsOfService) update);
                break;

            case TdApi.UpdateUsersNearby.CONSTRUCTOR:
                handleUsersNearby((TdApi.UpdateUsersNearby) update);
                break;

            case TdApi.UpdateDiceEmojis.CONSTRUCTOR:
                handleDiceEmojis((TdApi.UpdateDiceEmojis) update);
                break;

            case TdApi.UpdateAnimationSearchParameters.CONSTRUCTOR:
                handleAnimationSearchParameters((TdApi.UpdateAnimationSearchParameters) update);
                break;

            case TdApi.UpdateSuggestedActions.CONSTRUCTOR:
                handleSuggestedActions((TdApi.UpdateSuggestedActions) update);
                break;

            case TdApi.UpdateNewInlineQuery.CONSTRUCTOR:
                handleNewInlineQuery((TdApi.UpdateNewInlineQuery) update);
                break;

            case TdApi.UpdateNewChosenInlineResult.CONSTRUCTOR:
                handleNewChosenInlineResult((TdApi.UpdateNewChosenInlineResult) update);
                break;

            case TdApi.UpdateNewCallbackQuery.CONSTRUCTOR:
                handleNewCallbackQuery((TdApi.UpdateNewCallbackQuery) update);
                break;

            case TdApi.UpdateNewInlineCallbackQuery.CONSTRUCTOR:
                handleNewInlineCallbackQuery((TdApi.UpdateNewInlineCallbackQuery) update);
                break;

            case TdApi.UpdateNewShippingQuery.CONSTRUCTOR:
                handleNewShippingQuery((TdApi.UpdateNewShippingQuery) update);
                break;

            case TdApi.UpdateNewPreCheckoutQuery.CONSTRUCTOR:
                handleNewPreCheckoutQuery((TdApi.UpdateNewPreCheckoutQuery) update);
                break;

            case TdApi.UpdateNewCustomEvent.CONSTRUCTOR:
                handleNewCustomEvent((TdApi.UpdateNewCustomEvent) update);
                break;

            case TdApi.UpdateNewCustomQuery.CONSTRUCTOR:
                handleNewCustomQuery((TdApi.UpdateNewCustomQuery) update);
                break;

            case TdApi.UpdatePoll.CONSTRUCTOR:
                handlePoll((TdApi.UpdatePoll) update);
                break;

            case TdApi.UpdatePollAnswer.CONSTRUCTOR:
                handlePollAnswer((TdApi.UpdatePollAnswer) update);
                break;

            case TdApi.UpdateChatMember.CONSTRUCTOR:
                handleChatMember((TdApi.UpdateChatMember) update);
                break;

            case TdApi.UpdateNewChatJoinRequest.CONSTRUCTOR:
                handleNewChatJoinRequest((TdApi.UpdateNewChatJoinRequest) update);
                break;

            default:
                logDebug("Unhandled update: " + update.getClass().getSimpleName());
                break;

        }

        if (authorizationStateListener != null) {
            authorizationStateListener.onAuthorizationStateChanged(authorizationState);
        }

        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .postNotificationName(NotificationCenter.tdLibAuthorizationStateUpdated, authorizationState);
    }

    // === MÉTODOS DE MANEJO DE ESTADOS DE AUTORIZACIÓN ===

    private void handleWaitTdlibParameters() {
        FileLog.d(TAG + ": AuthorizationStateWaitTdlibParameters");

        TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
        parameters.databaseDirectory = new File(ApplicationLoader.getFilesDirFixed(),
            "telegraphy_tdlib_db_" + UserConfig.selectedAccount).getAbsolutePath();
        parameters.filesDirectory = new File(ApplicationLoader.getFilesDirFixed(),
            "telegraphy_tdlib_files_" + UserConfig.selectedAccount).getAbsolutePath();
        parameters.useMessageDatabase = true;
        parameters.useSecretChats = true;
        parameters.apiId = BuildVars.APP_ID;
        parameters.apiHash = BuildVars.APP_HASH;
        parameters.systemLanguageCode = LocaleController.getInstance().getSystemLocaleStringIso639();
        parameters.deviceModel = AndroidUtilities.getDeviceModel();
        parameters.systemVersion = AndroidUtilities.getSystemVersion();
        parameters.applicationVersion = BuildVars.BUILD_VERSION_STRING;
        parameters.enableStorageOptimizer = true;
        parameters.ignoreFileNames = false;
        parameters.useFileDatabase = true;
        parameters.useChatInfoDatabase = true;

        send(new TdApi.SetTdlibParameters(parameters), defaultHandler);
    }

    private void handleWaitEncryptionKey() {
        FileLog.d(TAG + ": AuthorizationStateWaitEncryptionKey");
        send(new TdApi.CheckDatabaseEncryptionKey(), defaultHandler);
    }

    private void handleWaitPhoneNumber() {
        FileLog.d(TAG + ": AuthorizationStateWaitPhoneNumber");
        haveAuthorization = false;
    }

    private void handleWaitCode(TdApi.AuthorizationStateWaitCode state) {
        FileLog.d(TAG + ": AuthorizationStateWaitCode");
        haveAuthorization = false;
    }

    private void handleWaitPassword(TdApi.AuthorizationStateWaitPassword state) {
        FileLog.d(TAG + ": AuthorizationStateWaitPassword");
        haveAuthorization = false;
    }

    private void handleWaitRegistration(TdApi.AuthorizationStateWaitRegistration state) {
        FileLog.d(TAG + ": AuthorizationStateWaitRegistration");
        haveAuthorization = false;
    }

    private void handleReady() {
        FileLog.d(TAG + ": AuthorizationStateReady - Logged in successfully");
        haveAuthorization = true;

        // Inicializar datos básicos después del login
        initializeAfterLogin();
    }

    private void handleLoggingOut() {
        FileLog.d(TAG + ": AuthorizationStateLoggingOut");
        haveAuthorization = false;
    }

    private void handleClosing() {
        FileLog.d(TAG + ": AuthorizationStateClosing");
        haveAuthorization = false;
    }

    private void handleClosed() {
        FileLog.d(TAG + ": AuthorizationStateClosed");
        client = null;
        isClientDestroyed = true;
        haveAuthorization = false;
        clearCaches();
    }

    /**
     * Inicializa datos después del login exitoso.
     */
    private void initializeAfterLogin() {
        // Obtener información del usuario actual
        getMe(result -> {
            if (result instanceof TdApi.User) {
                TdApi.User me = (TdApi.User) result;
                users.put(me.id, me);
                FileLog.d(TAG + ": Current user: " + me.firstName + " " + me.lastName);
            }
        });

        // Cargar chats principales
        loadChats();

        // Configurar notificaciones
        setupNotifications();
    }

    // === MÉTODOS DE MANEJO DE ACTUALIZACIONES ===

    private void handleNewMessage(TdApi.UpdateNewMessage update) {
        TdApi.Message message = update.message;
        databaseManager.addMessage(message.id, message.chatId, gson.toJson(message));

        if (messageListener != null) {
            messageListener.onNewMessage(message);
        }

        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .postNotificationName(NotificationCenter.messagesDidReceive, message);
    }

    private void handleNewChat(TdApi.UpdateNewChat update) {
        TdApi.Chat chat = update.chat;
        chats.put(chat.id, chat);
        databaseManager.addChat(chat.id, gson.toJson(chat));

        if (chatListener != null) {
            chatListener.onNewChat(chat);
        }

        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private void handleChatTitle(TdApi.UpdateChatTitle update) {
        TdApi.Chat chat = chats.get(update.chatId);
        if (chat != null) {
            chat.title = update.title;

            if (chatListener != null) {
                chatListener.onChatTitleChanged(chat);
            }
        }
    }

    private void handleChatPhoto(TdApi.UpdateChatPhoto update) {
        TdApi.Chat chat = chats.get(update.chatId);
        if (chat != null) {
            chat.photo = update.photo;

            if (chatListener != null) {
                chatListener.onChatPhotoChanged(chat);
            }
        }
    }

    private void handleChatLastMessage(TdApi.UpdateChatLastMessage update) {
        TdApi.Chat chat = chats.get(update.chatId);
        if (chat != null) {
            chat.lastMessage = update.lastMessage;

            if (chatListener != null) {
                chatListener.onChatLastMessageChanged(chat);
            }
        }
    }

    private void handleChatPosition(TdApi.UpdateChatPosition update) {
        TdApi.Chat chat = chats.get(update.chatId);
        if (chat != null) {
            // Actualizar posición del chat
            for (int i = 0; i < chat.positions.length; i++) {
                if (chat.positions[i].list.getConstructor() == update.position.list.getConstructor()) {
                    chat.positions[i] = update.position;
                    break;
                }
            }

            if (chatListener != null) {
                chatListener.onChatPositionChanged(chat);
            }
        }
    }

    private void handleChatReadInbox(TdApi.UpdateChatReadInbox update) {
        TdApi.Chat chat = chats.get(update.chatId);
        if (chat != null) {
            chat.lastReadInboxMessageId = update.lastReadInboxMessageId;
            chat.unreadCount = update.unreadCount;

            NotificationCenter.getInstance(UserConfig.selectedAccount)
                .postNotificationName(NotificationCenter.messagesDidRead, update.chatId);
        }
    }

    private void handleChatReadOutbox(TdApi.UpdateChatReadOutbox update) {
        TdApi.Chat chat = chats.get(update.chatId);
        if (chat != null) {
            chat.lastReadOutboxMessageId = update.lastReadOutboxMessageId;
        }
    }

    private void handleUserUpdate(TdApi.UpdateUser update) {
        users.put(update.user.id, update.user);
        databaseManager.addUser(update.user.id, gson.toJson(update.user));

        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .postNotificationName(NotificationCenter.userInfoDidLoad, update.user.id);
    }

    private void handleBasicGroupUpdate(TdApi.UpdateBasicGroup update) {
        basicGroups.put(update.basicGroup.id, update.basicGroup);
        databaseManager.addBasicGroup(update.basicGroup.id, gson.toJson(update.basicGroup));
    }

    private void handleSupergroupUpdate(TdApi.UpdateSupergroup update) {
        supergroups.put(update.supergroup.id, update.supergroup);
        databaseManager.addSupergroup(update.supergroup.id, gson.toJson(update.supergroup));
    }

    private void handleSecretChatUpdate(TdApi.UpdateSecretChat update) {
        secretChats.put(update.secretChat.id, update.secretChat);
        databaseManager.addSecretChat(update.secretChat.id, gson.toJson(update.secretChat));
    }

    private void handleCallUpdate(TdApi.UpdateCall update) {
        if (callListener != null) {
            callListener.onCallUpdate(update.call);
        }

        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .postNotificationName(NotificationCenter.callUpdated, update.call);
    }

    private void handleFileUpdate(TdApi.UpdateFile update) {
        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .postNotificationName(NotificationCenter.fileDidLoad, update.file);
    }

    private void handleFileDownloadUpdate(TdApi.UpdateFileDownload update) {
        if (fileDownloadListener != null) {
            fileDownloadListener.onFileDownloadUpdate(update);
        }
    }

    private void handleConnectionStateUpdate(TdApi.UpdateConnectionState update) {
        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .postNotificationName(NotificationCenter.connectionStateDidChange, update.state);
    }

    // === MÉTODOS DE ENVÍO Y COMUNICACIÓN ===

    /**
     * Handler por defecto para respuestas.
     */
    private static final Client.ResultHandler defaultHandler = new Client.ResultHandler() {
        @Override
        public void onResult(TdApi.Object object) {
            if (object instanceof TdApi.Error) {
                TdApi.Error error = (TdApi.Error) object;
                FileLog.e(TAG + ": Error: " + error.code + " - " + error.message);
            } else {
                FileLog.d(TAG + ": Result: " + object.toString());
            }
        }
    };

    /**
     * Envía una petición a TDLib.
     */


    public static void send(TdApi.Function function, Client.ResultHandler resultHandler) {
        if (client == null && function instanceof TdApi.SetTdlibParameters) {
            // Permite SetTdlibParameters incluso si el cliente aún no está completamente inicializado
             Client.create(new UpdatesHandler(), null, null).send(function, resultHandler != null ? resultHandler : defaultHandler);
        } else {
            getClient().send(function, resultHandler != null ? resultHandler : defaultHandler);
        }
    }

    /**
     * Envía una petición asíncrona a TDLib con CompletableFuture.
     */
    public CompletableFuture<TdApi.Object> sendAsync(TdApi.Function function) {
        int requestId = requestIdCounter.getAndIncrement();
        CompletableFuture<TdApi.Object> future = new CompletableFuture<>();

        pendingRequests.put(requestId, future);

        send(function, result -> {
            CompletableFuture<TdApi.Object> pendingFuture = pendingRequests.remove(requestId);
            if (pendingFuture != null) {
                if (result instanceof TdApi.Error) {
                    pendingFuture.completeExceptionally(new TdLibException((TdApi.Error) result));
                } else {
                    pendingFuture.complete(result);
                }
            }
        });

        return future;
    }

    // === MÉTODOS DE AUTENTICACIÓN ===

    /**
     * Establece el número de teléfono para autenticación.
     */
    public void setAuthenticationPhoneNumber(String phoneNumber) {
        TdApi.PhoneNumberAuthenticationSettings settings = new TdApi.PhoneNumberAuthenticationSettings();
        settings.allowFlashCall = false;
        settings.allowSmsRetrieverApi = false;
        settings.isCurrentPhoneNumber = true;
        settings.allowMissedCall = false;

        send(new TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings), defaultHandler);
    }

    /**
     * Verifica el código de autenticación.
     */
    public void checkAuthenticationCode(String code) {
        send(new TdApi.CheckAuthenticationCode(code), defaultHandler);
    }

    /**
     * Verifica la contraseña de 2FA.
     */
    public void checkAuthenticationPassword(String password) {
        send(new TdApi.CheckAuthenticationPassword(password), defaultHandler);
    }

    /**
     * Registra un nuevo usuario.
     */
    public void registerUser(String firstName, String lastName) {
        send(new TdApi.RegisterUser(firstName, lastName), defaultHandler);
    }

    /**
     * Solicita reenvío del código de autenticación.
     */
    public void resendAuthenticationCode() {
        send(new TdApi.ResendAuthenticationCode(), defaultHandler);
    }

    /**
     * Cierra sesión.
     */
    public void logOut() {
        send(new TdApi.LogOut(), defaultHandler);
    }

    // === MÉTODOS DE USUARIO ===

    /**
     * Obtiene información del usuario actual.
     */
    public void getMe(Client.ResultHandler resultHandler) {
        send(new TdApi.GetMe(), resultHandler);
    }

    /**
     * Obtiene información de un usuario.
     */
    public void getUser(long userId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetUser(userId), resultHandler);
    }

    /**
     * Obtiene información del usuario actual de forma asíncrona.
     */
    public CompletableFuture<TdApi.User> getMeAsync() {
        return sendAsync(new TdApi.GetMe()).thenApply(result -> (TdApi.User) result);
    }

    /**
     * Busca usuarios por nombre de usuario.
     */
    public void searchPublicChat(String username, Client.ResultHandler resultHandler) {
        send(new TdApi.SearchPublicChat(username), resultHandler);
    }

    /**
     * Actualiza el perfil del usuario.
     */
    public void editProfile(String firstName, String lastName, String bio) {
        if (firstName != null) {
            send(new TdApi.SetName(firstName, lastName != null ? lastName : ""), defaultHandler);
        }
        if (bio != null) {
            send(new TdApi.SetBio(bio), defaultHandler);
        }
    }

    /**
     * Establece la foto de perfil.
     */
    public void setProfilePhoto(TdApi.InputChatPhoto photo) {
        send(new TdApi.SetProfilePhoto(photo), defaultHandler);
    }

    // === MÉTODOS DE CHATS ===

    /**
     * Carga los chats principales.
     */
    public void loadChats() {
        getChats(new TdApi.ChatListMain(), 50, null);
    }

    /**
     * Obtiene lista de chats.
     */
    public void getChats(TdApi.ChatList chatList, int limit, Client.ResultHandler resultHandler) {
        if (chatList == null) {
            chatList = new TdApi.ChatListMain();
        }

        send(new TdApi.GetChats(chatList, limit), result -> {
            if (result instanceof TdApi.Chats) {
                TdApi.Chats chats = (TdApi.Chats) result;
                FileLog.d(TAG + ": Loaded " + chats.chatIds.length + " chats");

                // Cargar información detallada de cada chat
                for (long chatId : chats.chatIds) {
                    getChat(chatId, null);
                }

                NotificationCenter.getInstance(UserConfig.selectedAccount)
                    .postNotificationName(NotificationCenter.dialogsNeedReload);
            }

            if (resultHandler != null) {
                resultHandler.onResult(result);
            }
        });
    }

    /**
     * Obtiene información de un chat.
     */
    public void getChat(long chatId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetChat(chatId), result -> {
            if (result instanceof TdApi.Chat) {
                TdApi.Chat chat = (TdApi.Chat) result;
                chats.put(chatId, chat);
            }

            if (resultHandler != null) {
                resultHandler.onResult(result);
            }
        });
    }

    /**
     * Crea un nuevo chat privado.
     */
    public void createPrivateChat(long userId, boolean force, Client.ResultHandler resultHandler) {
        send(new TdApi.CreatePrivateChat(userId, force), resultHandler);
    }

    /**
     * Crea un nuevo grupo básico.
     */
    public void createNewBasicGroupChat(long[] userIds, String title, Client.ResultHandler resultHandler) {
        send(new TdApi.CreateNewBasicGroupChat(userIds, title), resultHandler);
    }

    /**
     * Crea un nuevo supergrupo.
     */
    public void createNewSupergroupChat(String title, boolean isChannel, String description,
                                       TdApi.ChatLocation location, boolean forImport,
                                       Client.ResultHandler resultHandler) {
        send(new TdApi.CreateNewSupergroupChat(title, isChannel, description, location, forImport), resultHandler);
    }

    /**
     * Obtiene miembros de un chat.
     */
    public void getChatMembers(long chatId, TdApi.ChatMembersFilter filter, int offset, int limit,
                               Client.ResultHandler resultHandler) {
        send(new TdApi.GetChatMembers(chatId, filter, offset, limit), resultHandler);
    }

    /**
     * Añade miembros a un chat.
     */
    public void addChatMembers(long chatId, long[] userIds, Client.ResultHandler resultHandler) {
        send(new TdApi.AddChatMembers(chatId, userIds), resultHandler);
    }

    /**
     * Elimina un miembro del chat.
     */
    public void setChatMemberStatus(long chatId, TdApi.MessageSender memberId, TdApi.ChatMemberStatus status,
                                    Client.ResultHandler resultHandler) {
        send(new TdApi.SetChatMemberStatus(chatId, memberId, status), resultHandler);
    }

    /**
     * Establece el título de un chat.
     */
    public void setChatTitle(long chatId, String title, Client.ResultHandler resultHandler) {
        send(new TdApi.SetChatTitle(chatId, title), resultHandler);
    }

    /**
     * Establece la descripción de un chat.
     */
    public void setChatDescription(long chatId, String description, Client.ResultHandler resultHandler) {
        send(new TdApi.SetChatDescription(chatId, description), resultHandler);
    }

    /**
     * Establece la foto de un chat.
     */
    public void setChatPhoto(long chatId, TdApi.InputChatPhoto photo, Client.ResultHandler resultHandler) {
        send(new TdApi.SetChatPhoto(chatId, photo), resultHandler);
    }

    // === MÉTODOS DE MENSAJES ===

    /**
     * Obtiene historial de mensajes de un chat.
     */
    public void getChatHistory(long chatId, long fromMessageId, int offset, int limit, boolean onlyLocal,
                               Client.ResultHandler resultHandler) {
        send(new TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, onlyLocal), resultHandler);
    }

    /**
     * Envía un mensaje de texto.
     */
    public void sendTextMessage(long chatId, String text, Client.ResultHandler resultHandler) {
        sendTextMessage(chatId, text, null, null, resultHandler);
    }

    /**
     * Envía un mensaje de texto con opciones adicionales.
     */
    public void sendTextMessage(long chatId, String text, TdApi.ReplyTo replyTo,
                                TdApi.MessageSendOptions options, Client.ResultHandler resultHandler) {
        TdApi.InputMessageText inputMessage = new TdApi.InputMessageText();
        inputMessage.text = new TdApi.FormattedText(text, null);
        inputMessage.disableWebPagePreview = false;
        inputMessage.clearDraft = true;

        sendMessage(chatId, 0, replyTo, options, inputMessage, resultHandler);
    }

    /**
     * Envía un mensaje.
     */
        public void sendMessage(long chatId, long messageThreadId, TdApi.ReplyTo replyTo,
                            TdApi.MessageSendOptions options, TdApi.InputMessageContent inputMessageContent,
                            Client.ResultHandler resultHandler) {
        if (options == null) {
            options = new TdApi.MessageSendOptions();
            options.disableNotification = false;
            options.fromBackground = false;
            options.protectContent = false;
            options.updateOrderOfInstalledStickerSets = true;
            options.schedulingState = null;
            options.sendingId = 0;
        }

        send(new TdApi.SendMessage(chatId, messageThreadId, replyTo, options, inputMessageContent), resultHandler);
    }

    /**
     * Envía un mensaje con archivo adjunto.
     */
    public void sendMediaMessage(long chatId, TdApi.InputFile file, TdApi.InputMessageContent content,
                                 Client.ResultHandler resultHandler) {
        sendMessage(chatId, 0, null, null, content, resultHandler);
    }

    /**
     * Envía una foto.
     */
    public void sendPhoto(long chatId, TdApi.InputFile photo, String caption, Client.ResultHandler resultHandler) {
        TdApi.InputMessagePhoto inputMessage = new TdApi.InputMessagePhoto();
        inputMessage.photo = photo;
        inputMessage.caption = new TdApi.FormattedText(caption != null ? caption : "", null);
        inputMessage.hasSpoiler = false;
        inputMessage.selfDestructType = null;

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Envía un video.
     */
    public void sendVideo(long chatId, TdApi.InputFile video, TdApi.InputThumbnail thumbnail,
                          String caption, int duration, int width, int height, Client.ResultHandler resultHandler) {
        TdApi.InputMessageVideo inputMessage = new TdApi.InputMessageVideo();
        inputMessage.video = video;
        inputMessage.thumbnail = thumbnail;
        inputMessage.caption = new TdApi.FormattedText(caption != null ? caption : "", null);
        inputMessage.duration = duration;
        inputMessage.width = width;
        inputMessage.height = height;
        inputMessage.supportsStreaming = true;
        inputMessage.hasSpoiler = false;
        inputMessage.selfDestructType = null;

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Envía un documento.
     */
    public void sendDocument(long chatId, TdApi.InputFile document, TdApi.InputThumbnail thumbnail,
                             String caption, Client.ResultHandler resultHandler) {
        TdApi.InputMessageDocument inputMessage = new TdApi.InputMessageDocument();
        inputMessage.document = document;
        inputMessage.thumbnail = thumbnail;
        inputMessage.caption = new TdApi.FormattedText(caption != null ? caption : "", null);
        inputMessage.disableContentTypeDetection = false;

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Envía un sticker.
     */
    public void sendSticker(long chatId, TdApi.InputFile sticker, TdApi.InputThumbnail thumbnail,
                            int width, int height, String emoji, Client.ResultHandler resultHandler) {
        TdApi.InputMessageSticker inputMessage = new TdApi.InputMessageSticker();
        inputMessage.sticker = sticker;
        inputMessage.thumbnail = thumbnail;
        inputMessage.width = width;
        inputMessage.height = height;
        inputMessage.emoji = emoji;

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Envía una nota de voz.
     */
    public void sendVoiceNote(long chatId, TdApi.InputFile voiceNote, int duration, byte[] waveform,
                              String caption, Client.ResultHandler resultHandler) {
        TdApi.InputMessageVoiceNote inputMessage = new TdApi.InputMessageVoiceNote();
        inputMessage.voiceNote = voiceNote;
        inputMessage.duration = duration;
        inputMessage.waveform = waveform;
        inputMessage.caption = new TdApi.FormattedText(caption != null ? caption : "", null);

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Envía un video note (círculo).
     */
    public void sendVideoNote(long chatId, TdApi.InputFile videoNote, TdApi.InputThumbnail thumbnail,
                              int duration, int length, Client.ResultHandler resultHandler) {
        TdApi.InputMessageVideoNote inputMessage = new TdApi.InputMessageVideoNote();
        inputMessage.videoNote = videoNote;
        inputMessage.thumbnail = thumbnail;
        inputMessage.duration = duration;
        inputMessage.length = length;

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Envía ubicación.
     */
    public void sendLocation(long chatId, TdApi.Location location, int livePeriod, int heading,
                             int proximityAlertRadius, Client.ResultHandler resultHandler) {
        TdApi.InputMessageLocation inputMessage = new TdApi.InputMessageLocation();
        inputMessage.location = location;
        inputMessage.livePeriod = livePeriod;
        inputMessage.heading = heading;
        inputMessage.proximityAlertRadius = proximityAlertRadius;

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Envía contacto.
     */
    public void sendContact(long chatId, TdApi.Contact contact, Client.ResultHandler resultHandler) {
        TdApi.InputMessageContact inputMessage = new TdApi.InputMessageContact();
        inputMessage.contact = contact;

        sendMessage(chatId, 0, null, null, inputMessage, resultHandler);
    }

    /**
     * Reenvía mensajes.
     */
    public void forwardMessages(long chatId, long fromChatId, long[] messageIds,
                                TdApi.MessageSendOptions options, boolean sendCopy, boolean removeCaption,
                                Client.ResultHandler resultHandler) {
        send(new TdApi.ForwardMessages(chatId, 0, fromChatId, messageIds, options, sendCopy, removeCaption), resultHandler);
    }

    /**
     * Edita el texto de un mensaje.
     */
    public void editMessageText(long chatId, long messageId, TdApi.ReplyMarkup replyMarkup,
                                TdApi.InputMessageContent inputMessageContent, Client.ResultHandler resultHandler) {
        send(new TdApi.EditMessageText(chatId, messageId, replyMarkup, inputMessageContent), resultHandler);
    }

    /**
     * Edita el caption de un mensaje.
     */
    public void editMessageCaption(long chatId, long messageId, TdApi.ReplyMarkup replyMarkup,
                                   TdApi.FormattedText caption, Client.ResultHandler resultHandler) {
        send(new TdApi.EditMessageCaption(chatId, messageId, replyMarkup, caption), resultHandler);
    }

    /**
     * Elimina mensajes.
     */
    public void deleteMessages(long chatId, long[] messageIds, boolean revoke, Client.ResultHandler resultHandler) {
        send(new TdApi.DeleteMessages(chatId, messageIds, revoke), resultHandler);
    }

    /**
     * Marca mensajes como leídos.
     */
    public void viewMessages(long chatId, long[] messageIds, boolean forceRead, Client.ResultHandler resultHandler) {
        send(new TdApi.ViewMessages(chatId, messageIds, null, forceRead), resultHandler);
    }

    /**
     * Obtiene un mensaje específico.
     */
    public void getMessage(long chatId, long messageId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetMessage(chatId, messageId), resultHandler);
    }

    /**
     * Busca mensajes en un chat.
     */
    public void searchChatMessages(long chatId, String query, TdApi.MessageSender senderId,
                                   long fromMessageId, int offset, int limit, TdApi.SearchMessagesFilter filter,
                                   long messageThreadId, Client.ResultHandler resultHandler) {
        send(new TdApi.SearchChatMessages(chatId, query, senderId, fromMessageId, offset, limit, filter, messageThreadId), resultHandler);
    }

    /**
     * Busca mensajes globalmente.
     */
    public void searchMessages(TdApi.ChatList chatList, String query, int offset, int limit,
                               TdApi.SearchMessagesFilter filter, int minDate, int maxDate,
                               Client.ResultHandler resultHandler) {
        send(new TdApi.SearchMessages(chatList, query, offset, limit, filter, minDate, maxDate), resultHandler);
    }

    /**
     * Marca un chat como leído.
     */
    public void markChatAsRead(long chatId, Client.ResultHandler resultHandler) {
        send(new TdApi.ReadAllChatMentions(chatId), resultHandler);
    }

    // === MÉTODOS DE ARCHIVOS ===

    /**
     * Descarga un archivo.
     */
    public void downloadFile(int fileId, int priority, int offset, int limit, boolean synchronous,
                             Client.ResultHandler resultHandler) {
        send(new TdApi.DownloadFile(fileId, priority, offset, limit, synchronous), resultHandler);
    }

    /**
     * Sube un archivo.
     */
    public void uploadFile(TdApi.InputFile file, TdApi.FileType fileType, int priority,
                           Client.ResultHandler resultHandler) {
        send(new TdApi.UploadFile(file, fileType, priority), resultHandler);
    }

    /**
     * Cancela la descarga de un archivo.
     */
    public void cancelDownloadFile(int fileId, boolean onlyIfPending, Client.ResultHandler resultHandler) {
        send(new TdApi.CancelDownloadFile(fileId, onlyIfPending), resultHandler);
    }

    /**
     * Obtiene información de un archivo.
     */
    public void getFile(int fileId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetFile(fileId), resultHandler);
    }

    /**
     * Obtiene información de un archivo remoto.
     */
    public void getRemoteFile(String remoteFileId, TdApi.FileType fileType, Client.ResultHandler resultHandler) {
        send(new TdApi.GetRemoteFile(remoteFileId, fileType), resultHandler);
    }

    // --- Implementación de métodos JNI (simulada o a ser completada si es necesario) ---
    // Si la librería org.drinkless.td.libcore.telegram ya maneja toda la comunicación nativa,
    // no se necesitarían métodos JNI explícitos aquí para la comunicación C++.
    // La conexión con la capa nativa se da a través del objeto `Client`.

    /**
     * Ejemplo de cómo podrías llamar a una función nativa si fuera necesario (no es el caso con TDLib normalmente).
     * private static native void nativeConnectToProductionServer(String params);
     *
     * public static void connectToProductionServer() {
     *     // Parámetros de AndroidUtilities.java
     *     String connectionParams = AndroidUtilities.getProductionConnectionParams();
     *     nativeConnectToProductionServer(connectionParams);
     * }
     */

     public static void nativeConnectionToProductionServer() {
        // Parámetros de AndroidUtilities.java
          String connectionParams = AndroidUtilities.getProductionConnectionParams();
          clientTdLib.nativeConnectToProductionServer(connectionParams);
     }

    // === MÉTODOS DE LLAMADAS ===

    /**
     * Crea una llamada.
     */
    public void createCall(long userId, TdApi.CallProtocol protocol, boolean isVideo, Client.ResultHandler resultHandler) {
        send(new TdApi.CreateCall(userId, protocol, isVideo), resultHandler);
    }

    /**
     * Acepta una llamada.
     */
    public void acceptCall(int callId, TdApi.CallProtocol protocol, Client.ResultHandler resultHandler) {
        send(new TdApi.AcceptCall(callId, protocol), resultHandler);
    }

    /**
     * Finaliza una llamada.
     */
    public void discardCall(int callId, boolean isDisconnected, int duration, boolean isVideo,
                            long connectionId, Client.ResultHandler resultHandler) {
        send(new TdApi.DiscardCall(callId, isDisconnected, duration, isVideo, connectionId), resultHandler);
    }

    /**
     * Envía rating de llamada.
     */
    public void sendCallRating(int callId, int rating, String comment, TdApi.CallProblem[] problems,
                               Client.ResultHandler resultHandler) {
        send(new TdApi.SendCallRating(callId, rating, comment, problems), resultHandler);
    }

    /**
     * Envía debug de llamada.
     */
    public void sendCallDebugInformation(int callId, String debugInformation, Client.ResultHandler resultHandler) {
        send(new TdApi.SendCallDebugInformation(callId, debugInformation), resultHandler);
    }

    // === MÉTODOS DE STICKERS ===

    /**
     * Obtiene stickers instalados.
     */
    public void getInstalledStickerSets(TdApi.StickerType stickerType, Client.ResultHandler resultHandler) {
        send(new TdApi.GetInstalledStickerSets(stickerType), resultHandler);
    }

    /**
     * Obtiene stickers trending.
     */
    public void getTrendingStickerSets(TdApi.StickerType stickerType, int offset, int limit,
                                       Client.ResultHandler resultHandler) {
        send(new TdApi.GetTrendingStickerSets(stickerType, offset, limit), resultHandler);
    }

    /**
     * Busca stickers.
     */
    public void searchStickers(TdApi.StickerType stickerType, String query, int limit,
                               Client.ResultHandler resultHandler) {
        send(new TdApi.SearchStickers(stickerType, query, limit), resultHandler);
    }

    /**
     * Obtiene set de stickers.
     */
    public void getStickerSet(long setId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetStickerSet(setId), resultHandler);
    }

    /**
     * Instala set de stickers.
     */
    public void changeStickerSet(long setId, boolean isInstalled, boolean isArchived,
                                 Client.ResultHandler resultHandler) {
        send(new TdApi.ChangeStickerSet(setId, isInstalled, isArchived), resultHandler);
    }

    // === MÉTODOS DE BOTS ===

    /**
     * Obtiene información de un bot.
     */
    public void getBotInfo(long botUserId, String languageCode, Client.ResultHandler resultHandler) {
        send(new TdApi.GetBotInfo(botUserId, languageCode), resultHandler);
    }

    /**
     * Envía consulta inline a un bot.
     */
    public void getInlineQueryResults(long botUserId, long chatId, TdApi.Location userLocation,
                                      String query, String offset, Client.ResultHandler resultHandler) {
        send(new TdApi.GetInlineQueryResults(botUserId, chatId, userLocation, query, offset), resultHandler);
    }

    /**
     * Responde a consulta inline.
     */
    public void answerInlineQuery(long inlineQueryId, boolean isPersonal, TdApi.InlineQueryResult[] results,
                                  int cacheTime, String nextOffset, TdApi.InlineQueryResultsButton button,
                                  Client.ResultHandler resultHandler) {
        send(new TdApi.AnswerInlineQuery(inlineQueryId, isPersonal, results, cacheTime, nextOffset, button), resultHandler);
    }

    /**
     * Obtiene callback answer.
     */
    public void getCallbackQueryAnswer(long chatId, long messageId, TdApi.CallbackQueryPayload payload,
                                       Client.ResultHandler resultHandler) {
        send(new TdApi.GetCallbackQueryAnswer(chatId, messageId, payload), resultHandler);
    }

    /**
     * Responde a callback query.
     */
    public void answerCallbackQuery(long callbackQueryId, String text, boolean showAlert, String url,
                                    int cacheTime, Client.ResultHandler resultHandler) {
        send(new TdApi.AnswerCallbackQuery(callbackQueryId, text, showAlert, url, cacheTime), resultHandler);
    }

    // === MÉTODOS DE CANALES ===

    /**
     * Obtiene información de un canal.
     */
    public void getSupergroupFullInfo(long supergroupId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetSupergroupFullInfo(supergroupId), resultHandler);
    }

    /**
     * Obtiene estadísticas del canal.
     */
    public void getChatStatistics(long chatId, boolean isDark, Client.ResultHandler resultHandler) {
        send(new TdApi.GetChatStatistics(chatId, isDark), resultHandler);
    }

    /**
     * Obtiene estadísticas de mensaje.
     */
    public void getMessageStatistics(long chatId, long messageId, boolean isDark, Client.ResultHandler resultHandler) {
        send(new TdApi.GetMessageStatistics(chatId, messageId, isDark), resultHandler);
    }

    /**
     * Reporta un supergrupo.
     */
    public void reportSupergroupSpam(long supergroupId, long[] messageIds, Client.ResultHandler resultHandler) {
        send(new TdApi.ReportSupergroupSpam(supergroupId, messageIds), resultHandler);
    }

    // === MÉTODOS DE GRUPOS CON TOPICS ===

    /**
     * Obtiene topics de un foro.
     */
    public void getForumTopics(long chatId, String query, int offsetDate, long offsetMessageId,
                               long offsetMessageThreadId, int limit, Client.ResultHandler resultHandler) {
        send(new TdApi.GetForumTopics(chatId, query, offsetDate, offsetMessageId, offsetMessageThreadId, limit), resultHandler);
    }

    /**
     * Crea un topic en un foro.
     */
    public void createForumTopic(long chatId, String name, TdApi.ForumTopicIcon icon, Client.ResultHandler resultHandler) {
        send(new TdApi.CreateForumTopic(chatId, name, icon), resultHandler);
    }

    /**
     * Edita un topic de foro.
     */
    public void editForumTopic(long chatId, long messageThreadId, String name, boolean editIconCustomEmoji,
                               String iconCustomEmojiId, Client.ResultHandler resultHandler) {
        send(new TdApi.EditForumTopic(chatId, messageThreadId, name, editIconCustomEmoji, iconCustomEmojiId), resultHandler);
    }

    /**
     * Cierra un topic de foro.
     */
    public void closeForumTopic(long chatId, long messageThreadId, Client.ResultHandler resultHandler) {
        send(new TdApi.CloseForumTopic(chatId, messageThreadId), resultHandler);
    }

    /**
     * Abre un topic de foro.
     */
    public void reopenForumTopic(long chatId, long messageThreadId, Client.ResultHandler resultHandler) {
        send(new TdApi.ReopenForumTopic(chatId, messageThreadId), resultHandler);
    }

    /**
     * Elimina un topic de foro.
     */
    public void deleteForumTopic(long chatId, long messageThreadId, Client.ResultHandler resultHandler) {
        send(new TdApi.DeleteForumTopic(chatId, messageThreadId), resultHandler);
    }

    // === MÉTODOS DE STORIES ===

    /**
     * Obtiene stories activas.
     */
    public void getActiveStories(TdApi.StoryList storyList, Client.ResultHandler resultHandler) {
        send(new TdApi.GetActiveStories(storyList), resultHandler);
    }

    /**
     * Obtiene stories de un chat.
     */
    public void getChatActiveStories(long chatId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetChatActiveStories(chatId), resultHandler);
    }

    /**
     * Obtiene una story específica.
     */
    public void getStory(long storySenderChatId, int storyId, boolean onlyLocal, Client.ResultHandler resultHandler) {
        send(new TdApi.GetStory(storySenderChatId, storyId, onlyLocal), resultHandler);
    }

    /**
     * Envía una story.
     */
    public void sendStory(TdApi.InputStoryContent content, TdApi.StoryPrivacySettings privacySettings,
                          int activePeriod, boolean isPostedToChatPage, boolean protectContent,
                          Client.ResultHandler resultHandler) {
        send(new TdApi.SendStory(content, privacySettings, activePeriod, isPostedToChatPage, protectContent), resultHandler);
    }

    /**
     * Edita una story.
     */
    public void editStory(int storyId, TdApi.InputStoryContent content, TdApi.StoryPrivacySettings privacySettings,
                          Client.ResultHandler resultHandler) {
        send(new TdApi.EditStory(storyId, content, privacySettings), resultHandler);
    }

    /**
     * Elimina una story.
     */
    public void deleteStory(int storyId, Client.ResultHandler resultHandler) {
        send(new TdApi.DeleteStory(storyId), resultHandler);
    }

    /**
     * Ve una story.
     */
    public void viewStory(long storySenderChatId, int storyId, Client.ResultHandler resultHandler) {
        send(new TdApi.ViewStory(storySenderChatId, storyId), resultHandler);
    }

    // === MÉTODOS DE BUSINESS ===

    /**
     * Obtiene información de cuenta de negocio.
     */
    public void getBusinessConnection(String connectionId, Client.ResultHandler resultHandler) {
        send(new TdApi.GetBusinessConnection(connectionId), resultHandler);
    }

    /**
     * Obtiene chats de negocio.
     */
    public void getBusinessConnectedBot(Client.ResultHandler resultHandler) {
        send(new TdApi.GetBusinessConnectedBot(), resultHandler);
    }

    // === MÉTODOS DE STARS (PAGOS) ===

    /**
     * Obtiene balance de stars.
     */
    public void getStarTransactions(TdApi.MessageSender ownerId, String offset, int limit,
                                    TdApi.StarTransactionDirection direction, Client.ResultHandler resultHandler) {
        send(new TdApi.GetStarTransactions(ownerId, offset, limit, direction), resultHandler);
    }

    /**
     * Compra stars.
     */
    public void refillStars(long starCount, String currency, Client.ResultHandler resultHandler) {
        send(new TdApi.RefillStars(starCount, currency), resultHandler);
    }

    // === MÉTODOS DE GIFTS ===

    /**
     * Obtiene gifts disponibles.
     */
    public void getAvailableGifts(Client.ResultHandler resultHandler) {
        send(new TdApi.GetAvailableGifts(), resultHandler);
    }

    /**
     * Envía un gift.
     */
    public void sendGift(long giftId, long userId, TdApi.FormattedText text, boolean isPrivate,
                         Client.ResultHandler resultHandler) {
        send(new TdApi.SendGift(giftId, userId, text, isPrivate), resultHandler);
    }

    // === MÉTODOS DE NOTIFICACIONES ===

    /**
     * Configura notificaciones.
     */
    private void setupNotifications() {
        // Configurar notificaciones push
        TdApi.DeviceToken deviceToken = new TdApi.DeviceTokenFirebaseCloudMessaging(
            BuildVars.FIREBASE_TOKEN != null ? BuildVars.FIREBASE_TOKEN : "",
            false
        );

        send(new TdApi.RegisterDevice(deviceToken, new long[0]), result -> {
            if (result instanceof TdApi.PushReceiverId) {
                TdApi.PushReceiverId pushReceiverId = (TdApi.PushReceiverId) result;
                FileLog.d(TAG + ": Push receiver ID: " + pushReceiverId.id);
            }
        });

        // Configurar configuración de notificaciones
        TdApi.ScopeNotificationSettings scopeSettings = new TdApi.ScopeNotificationSettings();
        scopeSettings.muteFor = 0;
        scopeSettings.sound = "default";
        scopeSettings.showPreview = true;
        scopeSettings.disablePinnedMessageNotifications = false;
        scopeSettings.disableMentionNotifications = false;

        send(new TdApi.SetScopeNotificationSettings(new TdApi.NotificationSettingsScopePrivateChats(), scopeSettings), defaultHandler);
        send(new TdApi.SetScopeNotificationSettings(new TdApi.NotificationSettingsScopeGroupChats(), scopeSettings), defaultHandler);
        send(new TdApi.SetScopeNotificationSettings(new TdApi.NotificationSettingsScopeChannelChats(), scopeSettings), defaultHandler);
    }

    /**
     * Obtiene configuración de notificaciones.
     */
    public void getScopeNotificationSettings(TdApi.NotificationSettingsScope scope, Client.ResultHandler resultHandler) {
        send(new TdApi.GetScopeNotificationSettings(scope), resultHandler);
    }

    /**
     * Establece configuración de notificaciones.
     */
    public void setScopeNotificationSettings(TdApi.NotificationSettingsScope scope,
                                             TdApi.ScopeNotificationSettings settings, Client.ResultHandler resultHandler) {
        send(new TdApi.SetScopeNotificationSettings(scope, settings), resultHandler);
    }

    // === MÉTODOS DE CONFIGURACIÓN ===

    /**
     * Obtiene opciones de configuración.
     */
    public void getOption(String name, Client.ResultHandler resultHandler) {
        send(new TdApi.GetOption(name), resultHandler);
    }

    /**
     * Establece opciones de configuración.
     */
    public void setOption(String name, TdApi.OptionValue value, Client.ResultHandler resultHandler) {
        send(new TdApi.SetOption(name, value), resultHandler);
    }

    // === MÉTODOS DE BÚSQUEDA ===

    /**
     * Busca chats.
     */
    public void searchChats(String query, int limit, Client.ResultHandler resultHandler) {
        send(new TdApi.SearchChats(query, limit), resultHandler);
    }

    /**
     * Busca chats públicos.
     */
    public void searchPublicChats(String query, Client.ResultHandler resultHandler) {
        send(new TdApi.SearchPublicChats(query), resultHandler);
    }

    /**
     * Busca contactos.
     */
    public void searchContacts(String query, int limit, Client.ResultHandler resultHandler) {
        send(new TdApi.SearchContacts(query, limit), resultHandler);
    }

    // === MÉTODOS DE UTILIDAD ===

    /**
     * Obtiene el estado de autorización actual.
     */
    public TdApi.AuthorizationState getAuthorizationState() {
        return authorizationState;
    }

    /**
     * Verifica si hay autorización.
     */
    public boolean haveAuthorization() {
        return haveAuthorization;
    }

    /**
     * Verifica si el cliente está destruido.
     */
    public boolean isClientDestroyed() {
        return isClientDestroyed;
    }

    /**
     * Obtiene un chat del cache.
     */
    public TdApi.Chat getChat(long chatId) {
        TdApi.Chat chat = chats.get(chatId);
        if (chat == null) {
            String chatJson = databaseManager.getChat(chatId);
            if (chatJson != null) {
                chat = gson.fromJson(chatJson, TdApi.Chat.class);
                chats.put(chatId, chat);
            }
        }
        return chat;
    }

    /**
     * Obtiene un usuario del cache.
     */
    public TdApi.User getUser(long userId) {
        TdApi.User user = users.get(userId);
        if (user == null) {
            String userJson = databaseManager.getUser(userId);
            if (userJson != null) {
                user = gson.fromJson(userJson, TdApi.User.class);
                users.put(userId, user);
            }
        }
        return user;
    }

    /**
     * Obtiene un grupo básico del cache.
     */
    public TdApi.BasicGroup getBasicGroup(long groupId) {
        TdApi.BasicGroup basicGroup = basicGroups.get(groupId);
        if (basicGroup == null) {
            String basicGroupJson = databaseManager.getBasicGroup(groupId);
            if (basicGroupJson != null) {
                basicGroup = gson.fromJson(basicGroupJson, TdApi.BasicGroup.class);
                basicGroups.put(groupId, basicGroup);
            }
        }
        return basicGroup;
    }

    /**
     * Obtiene un supergrupo del cache.
     */
    public TdApi.Supergroup getSupergroup(long supergroupId) {
        TdApi.Supergroup supergroup = supergroups.get(supergroupId);
        if (supergroup == null) {
            String supergroupJson = databaseManager.getSupergroup(supergroupId);
            if (supergroupJson != null) {
                supergroup = gson.fromJson(supergroupJson, TdApi.Supergroup.class);
                supergroups.put(supergroupId, supergroup);
            }
        }
        return supergroup;
    }

    /**
     * Obtiene un chat secreto del cache.
     */
    public TdApi.SecretChat getSecretChat(long secretChatId) {
        TdApi.SecretChat secretChat = secretChats.get(secretChatId);
        if (secretChat == null) {
            String secretChatJson = databaseManager.getSecretChat(secretChatId);
            if (secretChatJson != null) {
                secretChat = gson.fromJson(secretChatJson, TdApi.SecretChat.class);
                secretChats.put(secretChatId, secretChat);
            }
        }
        return secretChat;
    }

    /**
     * Obtiene todos los chats del cache.
     */
    public Map<Long, TdApi.Chat> getAllChats() {
        return new ConcurrentHashMap<>(chats);
    }

    /**
     * Obtiene todos los usuarios del cache.
     */
    public Map<Long, TdApi.User> getAllUsers() {
        return new ConcurrentHashMap<>(users);
    }

    /**
     * Limpia todos los caches.
     */
    private void clearCaches() {
        chats.clear();
        users.clear();
        basicGroups.clear();
        supergroups.clear();
        secretChats.clear();
        pendingRequests.clear();
    }

    // === LISTENERS ===

    /**
     * Establece el listener de estados de autorización.
     */
    public void setAuthorizationStateListener(AuthorizationStateListener listener) {
        this.authorizationStateListener = listener;
    }

    /**
     * Establece el listener de mensajes.
     */
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    /**
     * Establece el listener de chats.
     */
    public void setChatListener(ChatListener listener) {
        this.chatListener = listener;
    }

    /**
     * Establece el listener de llamadas.
     */
    public void setCallListener(CallListener listener) {
        this.callListener = listener;
    }

    /**
     * Establece el listener de descargas de archivos.
     */
    public void setFileDownloadListener(FileDownloadListener listener) {
        this.fileDownloadListener = listener;
    }

    // === INTERFACES DE LISTENERS ===

    public interface AuthorizationStateListener {
        void onAuthorizationStateChanged(TdApi.AuthorizationState state);
    }

}

     Esta es la clase que quiero que integres, me habia equivocado anteriormente porque faltaba la implementacion ampliada de la clase TdApiManager.java, extend la clase DatabaseManager.java y agrega las tablas que faltan a partir de la clase TdAPIManager.java, y los metodos CRUD necesarios para integrarse con TdApiManager.java. En resumen, borra la clase TdApiManager.java anterior y copia esta clase que te estoy dando aqui, luego integrala con DatabaseManager.java, agregando a DatabaseManager.java todas las nuevas tablas a partir de las entidades que aparecen TdApiManager.java para utilizar tDLib Library acorde a Telegram Android Codebase como te muestro aqui.

You **must** respond now, using the `message_user` tool.
