/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.core.ApiFuture;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.firebase.ThreadManager.ThreadPools;
import com.google.firebase.auth.GoogleOAuthAccessToken;
import com.google.firebase.internal.ApiFutureImpl;
import com.google.firebase.internal.AuthStateListener;
import com.google.firebase.internal.FirebaseAppStore;
import com.google.firebase.internal.FirebaseService;
import com.google.firebase.internal.GetTokenResult;
import com.google.firebase.internal.NonNull;
import com.google.firebase.internal.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point of Firebase SDKs. It holds common configuration and state for Firebase APIs. Most
 * applications don't need to directly interact with FirebaseApp.
 *
 * <p>Firebase APIs use the default FirebaseApp by default, unless a different one is explicitly
 * passed to the API via FirebaseFoo.getInstance(firebaseApp).
 *
 * <p>{@link FirebaseApp#initializeApp(FirebaseOptions)} initializes the default app instance. This
 * method should be invoked at startup.
 */
public class FirebaseApp {

  private static final Logger logger = LoggerFactory.getLogger(FirebaseApp.class);

  /** A map of (name, FirebaseApp) instances. */
  private static final Map<String, FirebaseApp> instances = new HashMap<>();

  public static final String DEFAULT_APP_NAME = "[DEFAULT]";

  static final TokenRefresher.Factory DEFAULT_TOKEN_REFRESHER_FACTORY =
      new TokenRefresher.Factory();
  static final Clock DEFAULT_CLOCK = new Clock();

  /**
   * Global lock for synchronizing all SDK-wide application state changes. Specifically, any
   * accesses to instances map should be protected by this lock.
   */
  private static final Object appsLock = new Object();

  private final String name;
  private final FirebaseOptions options;
  private final TokenRefresher tokenRefresher;
  private final Clock clock;

  private final AtomicBoolean deleted = new AtomicBoolean();
  private final List<AuthStateListener> authStateListeners = new ArrayList<>();
  private final Map<String, FirebaseService> services = new HashMap<>();
  private final AtomicReference<ThreadPools> threadPools = new AtomicReference<>();

  private final ThreadManager threadManager;

  /**
   * Per application lock for synchronizing all internal FirebaseApp state changes.
   */
  private final Object lock = new Object();

  /** Default constructor. */
  private FirebaseApp(String name, FirebaseOptions options,
      TokenRefresher.Factory factory, Clock clock) {
    checkArgument(!Strings.isNullOrEmpty(name));
    this.name = name;
    this.options = checkNotNull(options);
    this.tokenRefresher = checkNotNull(factory).create(this);
    this.clock = checkNotNull(clock);
    this.threadManager = options.getThreadManager();
  }

  /** Returns a list of all FirebaseApps. */
  public static List<FirebaseApp> getApps() {
    // TODO: reenable persistence. See b/28158809.
    synchronized (appsLock) {
      return ImmutableList.copyOf(instances.values());
    }
  }

  /**
   * Returns the default (first initialized) instance of the {@link FirebaseApp}.
   *
   * @throws IllegalStateException if the default app was not initialized.
   */
  @Nullable
  public static FirebaseApp getInstance() {
    return getInstance(DEFAULT_APP_NAME);
  }

  /**
   * Returns the instance identified by the unique name, or throws if it does not exist.
   *
   * @param name represents the name of the {@link FirebaseApp} instance.
   * @return the {@link FirebaseApp} corresponding to the name.
   * @throws IllegalStateException if the {@link FirebaseApp} was not initialized, either via {@link
   *     #initializeApp(FirebaseOptions, String)} or {@link #getApps()}.
   */
  public static FirebaseApp getInstance(@NonNull String name) {
    synchronized (appsLock) {
      FirebaseApp firebaseApp = instances.get(normalize(name));
      if (firebaseApp != null) {
        return firebaseApp;
      }

      List<String> availableAppNames = getAllAppNames();
      String availableAppNamesMessage;
      if (availableAppNames.isEmpty()) {
        availableAppNamesMessage = "";
      } else {
        availableAppNamesMessage =
            "Available app names: " + Joiner.on(", ").join(availableAppNames);
      }
      String errorMessage =
          String.format(
              "FirebaseApp with name %s doesn't exist. %s", name, availableAppNamesMessage);
      throw new IllegalStateException(errorMessage);
    }
  }

  /**
   * Initializes the default {@link FirebaseApp} instance. Same as {@link
   * #initializeApp(FirebaseOptions, String)}, but it uses {@link #DEFAULT_APP_NAME} as name. *
   *
   * <p>The creation of the default instance is automatically triggered at app startup time, if
   * Firebase configuration values are available from resources - populated from
   * google-services.json.
   */
  public static FirebaseApp initializeApp(FirebaseOptions options) {
    return initializeApp(options, DEFAULT_APP_NAME);
  }

  /**
   * A factory method to initialize a {@link FirebaseApp}.
   *
   * @param options represents the global {@link FirebaseOptions}
   * @param name unique name for the app. It is an error to initialize an app with an already
   *     existing name. Starting and ending whitespace characters in the name are ignored (trimmed).
   * @return an instance of {@link FirebaseApp}
   * @throws IllegalStateException if an app with the same name has already been initialized.
   */
  public static FirebaseApp initializeApp(FirebaseOptions options, String name) {
    return initializeApp(options, name, DEFAULT_TOKEN_REFRESHER_FACTORY, DEFAULT_CLOCK);
  }

  static FirebaseApp initializeApp(FirebaseOptions options, String name,
      TokenRefresher.Factory tokenRefresherFactory, Clock clock) {
    FirebaseAppStore appStore = FirebaseAppStore.initialize();
    String normalizedName = normalize(name);
    final FirebaseApp firebaseApp;
    synchronized (appsLock) {
      checkState(
          !instances.containsKey(normalizedName),
          "FirebaseApp name " + normalizedName + " already exists!");

      firebaseApp = new FirebaseApp(normalizedName, options, tokenRefresherFactory, clock);
      instances.put(normalizedName, firebaseApp);
    }

    appStore.persistApp(firebaseApp);

    return firebaseApp;
  }

  @VisibleForTesting
  static void clearInstancesForTest() {
    synchronized (appsLock) {
      // Copy the instances list before iterating, as delete() would attempt to remove from the
      // original list.
      for (FirebaseApp app : ImmutableList.copyOf(instances.values())) {
        app.delete();
      }
      instances.clear();
    }
  }

  /**
   * Returns persistence key. Exists to support getting {@link FirebaseApp} persistence key after
   * the app has been deleted.
   */
  static String getPersistenceKey(String name, FirebaseOptions options) {
    return BaseEncoding.base64Url().omitPadding().encode(name.getBytes(UTF_8));
  }

  /** Use this key to store data per FirebaseApp. */
  String getPersistenceKey() {
    return FirebaseApp.getPersistenceKey(getName(), getOptions());
  }

  private static List<String> getAllAppNames() {
    Set<String> allAppNames = new HashSet<>();
    synchronized (appsLock) {
      for (FirebaseApp app : instances.values()) {
        allAppNames.add(app.getName());
      }
      FirebaseAppStore appStore = FirebaseAppStore.getInstance();
      if (appStore != null) {
        allAppNames.addAll(appStore.getAllPersistedAppNames());
      }
    }
    List<String> sortedNameList = new ArrayList<>(allAppNames);
    Collections.sort(sortedNameList);
    return sortedNameList;
  }

  /** Normalizes the app name. */
  private static String normalize(@NonNull String name) {
    return checkNotNull(name).trim();
  }

  /** Returns the unique name of this app. */
  @NonNull
  public String getName() {
    checkNotDeleted();
    return name;
  }

  /** 
   * Returns the specified {@link FirebaseOptions}. 
   */
  @NonNull
  public FirebaseOptions getOptions() {
    checkNotDeleted();
    return options;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FirebaseApp)) {
      return false;
    }
    return name.equals(((FirebaseApp) o).getName());
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("name", name).toString();
  }

  /**
   * Deletes the {@link FirebaseApp} and all its data. All calls to this {@link FirebaseApp}
   * instance will throw once it has been called.
   *
   * <p>A no-op if delete was called before.
   */
  public void delete() {
    synchronized (lock) {
      boolean valueChanged = deleted.compareAndSet(false /* expected */, true);
      if (!valueChanged) {
        return;
      }

      for (FirebaseService service : services.values()) {
        service.destroy();
      }
      services.clear();
      authStateListeners.clear();
      tokenRefresher.cleanup();
    }

    synchronized (appsLock) {
      instances.remove(name);
    }

    FirebaseAppStore appStore = FirebaseAppStore.getInstance();
    if (appStore != null) {
      appStore.removeApp(name);
    }
  }

  private void checkNotDeleted() {
    checkState(!deleted.get(), "FirebaseApp was deleted %s", this);
  }

  private boolean refreshRequired(GoogleOAuthAccessToken oldToken, boolean forceRefresh) {
    return oldToken == null || forceRefresh || oldToken.getExpiryTime() <= clock.now();
  }

  private final AtomicReference<GoogleOAuthAccessToken> current = new AtomicReference<>();

  GetTokenResult getToken(boolean forceRefresh) throws IOException {
    GoogleOAuthAccessToken currentToken = current.get();
    List<AuthStateListener> listenersCopy = null;
    if (refreshRequired(currentToken, forceRefresh)) {
      synchronized (lock) {
        currentToken = current.get();
        if (refreshRequired(currentToken, forceRefresh)) {
          currentToken = options.getCredential().getAccessToken();
          current.set(currentToken);
          listenersCopy = ImmutableList.copyOf(authStateListeners);

          long refreshDelay  = currentToken.getExpiryTime() - clock.now()
              - TimeUnit.MINUTES.toMillis(5);
          if (refreshDelay > 0) {
            tokenRefresher.scheduleRefresh(refreshDelay);
          } else {
            logger.warn("Token expiry ({}) is less than 5 minutes in the future. Not "
                + "scheduling a proactive refresh.", currentToken.getExpiryTime());
          }
        }
      }
    }

    GetTokenResult result = new GetTokenResult(currentToken.getAccessToken());
    if (listenersCopy != null) {
      for (AuthStateListener listener : listenersCopy) {
        listener.onAuthStateChanged(result);
      }
    }
    return result;
  }

  /**
   * Internal-only method to fetch a valid Service Account OAuth2 Token.
   *
   * @param forceRefresh force refreshes the token. Should only be set to <code>true</code> if the
   *     token is invalidated out of band.
   * @return a ListenableFuture
   */
  ApiFuture<GetTokenResult> getTokenAsync(final boolean forceRefresh) {
    return call(new Callable<GetTokenResult>() {
      @Override
      public GetTokenResult call() throws Exception {
        return getToken(forceRefresh);
      }
    });
  }

  private ThreadPools ensureThreadPools() {
    ThreadPools pools = threadPools.get();
    if (pools == null) {
      synchronized (lock) {
        pools = threadPools.get();
        if (pools == null) {
          pools = threadManager.getThreadPools(this);
        }
      }
    }
    return pools;
  }

  <T> ApiFuture<T> call(Callable<T> command) {
    checkNotNull(command);
    ListenableFuture<T> future = ensureThreadPools().executor.submit(command);
    return new ApiFutureImpl<>(future);
  }

  <T> ApiFuture<T> schedule(Callable<T> command, long delayMillis) {
    checkNotNull(command);
    ListenableScheduledFuture<T> future = ensureThreadPools()
        .scheduledExecutor.schedule(command, delayMillis, TimeUnit.MILLISECONDS);
    return new ApiFutureImpl<>(future);
  }

  ThreadFactory getDatabaseThreadFactory() {
    return threadManager.getDatabaseThreadFactory();
  }

  boolean isDefaultApp() {
    return DEFAULT_APP_NAME.equals(getName());
  }

  void addAuthStateListener(@NonNull final AuthStateListener listener) {
    GoogleOAuthAccessToken currentToken;
    synchronized (lock) {
      checkNotDeleted();
      authStateListeners.add(checkNotNull(listener));
      currentToken = this.current.get();
    }

    if (currentToken != null) {
      // Task has copied the authStateListeners before the listener was added.
      // Notify this listener explicitly.
      listener.onAuthStateChanged(new GetTokenResult(currentToken.getAccessToken()));
    }
  }

  void removeAuthStateListener(@NonNull AuthStateListener listener) {
    synchronized (lock) {
      checkNotDeleted();
      authStateListeners.remove(checkNotNull(listener));
    }
  }

  void addService(FirebaseService service) {
    synchronized (lock) {
      checkNotDeleted();
      checkArgument(!services.containsKey(checkNotNull(service).getId()));
      services.put(service.getId(), service);
    }
  }

  FirebaseService getService(String id) {
    synchronized (lock) {
      checkArgument(!Strings.isNullOrEmpty(id));
      return services.get(id);
    }
  }

  /**
   * Utility class for scheduling proactive token refresh events.  Each FirebaseApp should have
   * its own instance of this class. This class is not thread safe. The caller (FirebaseApp) must
   * ensure that methods are called serially.
   */
  static class TokenRefresher {

    private final FirebaseApp firebaseApp;
    private ApiFuture<ApiFuture<GetTokenResult>> future;

    TokenRefresher(FirebaseApp app) {
      this.firebaseApp = checkNotNull(app);
    }

    /**
     * Schedule a forced token refresh to be executed after a specified duration.
     *
     * @param delayMillis Duration in milliseconds, after which the token should be forcibly
     *     refreshed.
     */
    final void scheduleRefresh(long delayMillis) {
      cancelPrevious();
      scheduleNext(
          new Callable<ApiFuture<GetTokenResult>>() {
            @Override
            public ApiFuture<GetTokenResult> call() throws Exception {
              return firebaseApp.getTokenAsync(true);
            }
          },
          delayMillis);
    }

    protected void cancelPrevious() {
      if (future != null) {
        future.cancel(true);
      }
    }

    protected void scheduleNext(Callable<ApiFuture<GetTokenResult>> task, long delayMillis) {
      try {
        future = firebaseApp.schedule(task, delayMillis);
      } catch (UnsupportedOperationException ignored) {
        // Cannot support task scheduling in the current runtime.
      }
    }

    protected void cleanup() {
      if (future != null) {
        future.cancel(true);
      }
    }

    static class Factory {
      TokenRefresher create(FirebaseApp app) {
        return new TokenRefresher(app);
      }
    }
  }

  static class Clock {
    long now() {
      return System.currentTimeMillis();
    }
  }
}
