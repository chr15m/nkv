#!/usr/bin/env -S npx nbb
(ns nkv
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [promesa.core :as p]
    ["nostr-tools/pool" :refer [useWebSocketImplementation SimplePool]]
    ["crypto" :as crypto]
    ["fs" :as fs]
    ["os" :as os]
    ["path" :as path]
    ["nostr-tools" :as nostr]
    ["nostr-tools/nip49" :as nip49]
    ["ws$default" :as ws]
    ["child_process" :as cp]
    ["qrcode-terminal$default" :as qrcode]))

(useWebSocketImplementation ws)

(def app-name "cx.mccormick.nkv")
(def config-file-path ".nkv")
(def nostr-kind 30078)
(def default-relays
  ["wss://relay.mccormick.cx"
   "wss://relay.damus.io"
   "wss://relay.nostr.band"])
(def resubscribe-backoff [10 10 10 20 20 30 60])
(def websocket-ping-ms 10000)

(def cli-options
  [["-w" "--watch" "Watch for changes and run a command on new values."]
   ["-i" "--init" "Create a new .nkv file with a new private key."]
   ["-s" "--silent" "Silence all output except for read values."]
   ["-q" "--qr" "Print a QR code of the encrypted nsec for syncing."]
   ["-h" "--help" "Show this help"]])

(def last-event
  (atom
    (js/Math.floor (/ (js/Date.now) 1000))))

; *** helper functions *** ;

(def log
  #js {:info js/console.error})

(defn shell-escape [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

; *** nostr stuff *** ;

(defn pubkey [sk-bytes]
  (nostr/getPublicKey sk-bytes))

(defn encrypt-content [sk-bytes clj-content]
  (nostr/nip04.encrypt sk-bytes (pubkey sk-bytes) (js/JSON.stringify (clj->js clj-content))))

(defn decrypt-content [sk-bytes encrypted-content]
  (try
    (let [decrypted (nostr/nip04.decrypt sk-bytes (pubkey sk-bytes) encrypted-content)]
      (js->clj (js/JSON.parse decrypted) :keywordize-keys true))
    (catch :default e
      (js/console.error "Failed to decrypt content:" e)
      nil)))

(defn encrypt-key-with-pw [sk-bytes pw]
  (try
    (nip49/encrypt sk-bytes pw)
    (catch :default e
      (js/console.error "Failed to encrypt key" e)
      nil)))

(defn decode-nsec [nsec-str]
  (when nsec-str
    (try
      (let [decoded (nostr/nip19.decode (str/trim nsec-str))]
        (when (= (aget decoded "type") "nsec")
          (aget decoded "data")))
      (catch :default e
        (js/console.error (str "Error decoding nsec: " e))
        nil))))

(defn hmac-sha256 [secret data]
  (-> (.createHmac crypto "sha256" secret)
      (.update data)
      (.digest "hex")))

(defn create-finalized-event [sk-bytes clj-content d-identifier]
  ((.-info log) "Creating event for d-identifier:" d-identifier "with content:" (pr-str clj-content))
  (let [encrypted-content (encrypt-content sk-bytes clj-content)
        d-tag-value (hmac-sha256 sk-bytes (str app-name ":" d-identifier))
        n-tag-value (hmac-sha256 sk-bytes app-name)
        event-template
        (clj->js
          {:kind nostr-kind
           :created_at (js/Math.floor (/ (js/Date.now) 1000))
           :tags [["d" d-tag-value]
                  ["n" n-tag-value]]
           :content encrypted-content})]
    ((.-info log) "event" event-template)
    (nostr/finalizeEvent event-template sk-bytes)))

(defn publish-event [event relays]
  ((.-info log) "Publishing event to relays:" (clj->js relays))
  (p/catch
    (p/let [pool (SimplePool.)
            published (js/Promise.allSettled (.publish pool (clj->js relays) event))
            success? (seq (filter #(= (aget % "status") "fulfilled")
                                  published))]
      ((.-info log) "published" published)
      (if success?
        ((.-info log) "Event published to at least one relay.")
        (do
          (js/console.error "No relay accepted the event.")
          (js/process.exit 1))))
    (fn [err]
      (js/console.error err))))

(defn received-event [sk-bytes command event]
  (let [decrypted (decrypt-content sk-bytes (aget event "content"))
        value (:value decrypted)]
    (when value
      ((.-info log) "New value received:" value)
      (let [cmd-str (str (str/join " " command) " " (shell-escape value))]
        ((.-info log) "Executing:" cmd-str)
        (let [child (cp/spawn cmd-str #js {:shell true :stdio "inherit"})]
          (.on child "error" (fn [err]
                               (js/console.error (str "Error executing command: " err))))
          (.on child "exit" (fn [code]
                              (when (not= code 0)
                                (js/console.error (str "Command exited with non-zero status: " code))))))))))

(defn health-check-looper [pool relays]
  (js/setTimeout
    (fn []
      #_ (js/console.error "Performing health check for all relays.")
      #_ (when-let [relays-map (.-relays pool)]
        (.forEach relays-map
                  (fn [relay url]
                    (js/console.log "relay" url (boolean (aget relay "connected")))
                    (when-let [ws (aget relay "ws")]
                      (js/console.log "websocket state" url
                                      (aget ws "readyState"))))))
      (let [open-websockets-count (if-let [relays-map (.-relays pool)]
                                    (->> (.values relays-map)
                                         js/Array.from
                                         (filter #(when-let [ws (aget % "ws")]
                                                    (= 1 (aget ws "readyState"))))
                                         count)
                                    0)]
        #_ (js/console.error "Open websockets count:" open-websockets-count)
        (when (> open-websockets-count 0)
          (p/let [ping-filter (clj->js {:ids [(-> (crypto/randomBytes 32) (.toString "hex"))]
                                        :limit 1})
                  result (p/any
                           [(p/delay
                              (- websocket-ping-ms 1000)
                              :timeout)
                            (.querySync
                              pool (clj->js relays)
                              ping-filter
                              #js {:maxWait
                                   (+ websocket-ping-ms 1000)})])]
            #_ (js/console.error "Heartbeat check:" (pr-str result))
            (if (= result :timeout)
              (do
                ((.-info log) "Closing pool.")
                (when-let [relays-map (.-relays pool)]
                  (.forEach relays-map
                            (fn [relay url]
                              #_ (js/console.error "checking for relay websocket" url)
                              (when-let [ws (aget relay "ws")]
                                ((.-info log) "Closing websocket:" url)
                                (.close ws)))))
                #_ (.destroy pool))
              (health-check-looper pool relays))))))
    websocket-ping-ms))

(defn subscribe-to-events [pool relays event-filter sk-bytes command]
  (let [sub (.subscribe pool (clj->js relays)
                        (doto event-filter
                          (aset "since" @last-event))
                        (clj->js {:onevent
                                  (fn [event]
                                    #_ (js/console.log @last-event (aget event "created_at"))
                                    (reset! last-event
                                            (inc (aget event "created_at")))
                                    (received-event sk-bytes command event))
                                  :onclose
                                  #((.-info log) "Subscription closed")
                                  :oneose
                                  #(do
                                     ((.-info log) "Subscription eose")
                                     (health-check-looper pool relays))}))]
    sub))

(defn refresh-pool [*state]
  (update *state :pool
          (fn [old-pool]
            (when old-pool (.destroy old-pool))
            (SimplePool.))))

(defn auto-reconnect-looper [*state re-subscribe-callback]
  (let [pool (:pool *state)
        statuses (.listConnectionStatus pool)
        connected-count (->> statuses
                             js/Object.fromEntries
                             (js->clj)
                             (vals)
                             (filter true?)
                             count)]
    #_ (js/console.log "auto-reconnect-looper" connected-count)
    #_ (js/console.log "relays connection status"
                       (js/Object.fromEntries (.listConnectionStatus pool)))
    #_ (when-let [relays-map (.-relays pool)]
      (.forEach relays-map
                (fn [relay url]
                  (js/console.log "relay" url (boolean (aget relay "connected"))))))
    (when
      (and
        (> connected-count 0)
        (not (> (:connected-count *state) 0)))
      ((.-info log) "Connected."))
    (let [*updated-state
          (if
            (> connected-count 0)
            (assoc *state
                   :reconnect nil
                   :connected-count connected-count)
            (if-let [[backoff-idx last-attempt-ms] (:reconnect *state)]
              (let [delay-s (nth resubscribe-backoff backoff-idx)]
                (if (> (js/Date.now) (+ last-attempt-ms (* delay-s 1000)))
                  (do
                    ((.-info log) "Attempting relay reconnection.")
                    (let [next-idx (min (inc backoff-idx) (dec (count resubscribe-backoff)))]
                        (-> *state
                            (refresh-pool)
                            (re-subscribe-callback)
                            (assoc :reconnect [next-idx (js/Date.now)]))))
                  *state))
              (do
                ((.-info log) "Connection to relays lost.")
                (assoc *state :reconnect [0 (js/Date.now)]))))]
      #_ (print "auto-reconnect-looper reconnect *state"
             (:reconnect *updated-state))
      (js/setTimeout
        #(auto-reconnect-looper *updated-state re-subscribe-callback)
        (*
         (js/Math.max (-> *updated-state :reconnect first) 1)
         1000)))))

(defn watch-key [sk-bytes pk-hex key-arg command relays]
  ((.-info log) (str "Watching key: " key-arg ", and running command: " (str/join " " command)))
  (let [pool (SimplePool.)
        d-tag-value (hmac-sha256 sk-bytes (str app-name ":" key-arg))
        n-tag-value (hmac-sha256 sk-bytes app-name)
        event-filter (clj->js {:authors [pk-hex]
                               :kinds [nostr-kind]
                               :#d [d-tag-value]
                               :#n [n-tag-value]
                               :since @last-event})
        re-subscribe (fn [*state]
                       (when-let [old-sub (:subscription *state)]
                         (.close old-sub))
                       (let [sub (subscribe-to-events (:pool *state) relays event-filter sk-bytes command)]
                         (assoc *state :subscription sub)))
        initial-state {:pool pool :reconnect nil}]
    (auto-reconnect-looper (re-subscribe initial-state) re-subscribe)
    ((.-info log) "Subscription started. Waiting for events...")))

; *** file stuff *** ;

(defn read-config [p]
  (when (fs/existsSync p)
    (try (js->clj (js/JSON.parse (fs/readFileSync p "utf-8"))
                  :keywordize-keys true)
         (catch :default _ nil))))

(defn generate-new-nkv-config [relays]
  (let [new-sk-bytes (nostr/generateSecretKey)
        new-nsec-str (nostr/nip19.nsecEncode new-sk-bytes)
        new-config {:nsec new-nsec-str :relays relays}]
    (fs/writeFileSync config-file-path
                      (js/JSON.stringify
                        (clj->js new-config) nil 2) "utf-8")
    ((.-info log) "Wrote config to" config-file-path)
    [new-sk-bytes "generated config"]))

(defn get-relays [config-from-file]
  (cond
    (not-empty (aget js/process.env "NKV_RELAYS"))
    [(str/split (str/trim (aget js/process.env "NKV_RELAYS")) #",") "environment"]
    (seq (:relays config-from-file))
    [(:relays config-from-file) (str "config file")]
    :else
    [default-relays "defaults"]))

(defn load-config []
  (let [home-config-path (path/join (os/homedir) ".nkv")
        [config-from-file loaded-path]
        (cond (fs/existsSync config-file-path)
              [(read-config config-file-path) config-file-path]
              (fs/existsSync home-config-path)
              [(read-config home-config-path) home-config-path]
              :else
              [nil nil])
        [relays relays-source]
        (get-relays config-from-file)
        [sk-bytes nsec-source]
        (let [sk-from-env (decode-nsec (aget js/process.env "NKV_NSEC"))
              sk-from-file (decode-nsec (:nsec config-from-file))]
          (cond
            sk-from-env [sk-from-env "environment"]
            sk-from-file [sk-from-file (str "config file")]
            :else
            (generate-new-nkv-config relays)))]
    ((.-info log) "Using nsec from" nsec-source "and relays from" relays-source)
    (when loaded-path
      ((.-info log) "Loaded config from" loaded-path))
    {:sk-bytes sk-bytes :relays relays}))

(defn write-value [sk-bytes key-arg value-arg relays]
  ((.-info log) (str "Attempting to write key: " key-arg ", value: " value-arg))
  (let [content {:value value-arg}
        event (create-finalized-event sk-bytes content key-arg)]
    (publish-event event relays)))

(defn read-value [sk-bytes pk-hex key-arg relays]
  ((.-info log) (str "Attempting to read key: " key-arg))
  ((.-info log) "pk-key" pk-hex)
  (p/catch
    (p/let [pool (SimplePool.)
            d-tag-value (hmac-sha256 sk-bytes (str app-name ":" key-arg))
            n-tag-value (hmac-sha256 sk-bytes app-name)
            query-filter (clj->js {:authors [pk-hex]
                                   :limit 1
                                   :kinds [nostr-kind]
                                   :#d [d-tag-value]
                                   :#n [n-tag-value]})
            events (.querySync pool (clj->js relays) query-filter)]
      (when (seq events)
        (let [sorted-events (sort-by #(aget % "created_at") > events)
              latest-event (first sorted-events)
              decrypted (decrypt-content sk-bytes (aget latest-event "content"))]
          ((.-info log) "Latest event content decrypted:" (clj->js decrypted))
          (when decrypted (:value decrypted)))))
    (fn [err] (js/console.error err))))

(defn handle-init []
  (if (fs/existsSync config-file-path)
    (do
      (js/console.error "Config already present in" config-file-path)
      (js/process.exit 1))
    (do
      (generate-new-nkv-config (first (get-relays nil)))
      (js/process.exit 0))))

(defn handle-qr [sk-bytes]
  (let [pin (-> (crypto/randomBytes 4)
                (.readUInt32BE 0)
                (mod 100000000)
                str
                (.padStart 8 "0"))
        encrypted-key (encrypt-key-with-pw sk-bytes pin)]
    (if encrypted-key
      (do
        ((.-info log) "Scan this QR code on your other device.")
        (.generate qrcode encrypted-key #js {:small true})
        (println (str "\nPin: " pin))
        (println encrypted-key)
        (js/process.exit 0))
      (do
        (js/console.error "Failed to encrypt key.")
        (js/process.exit 1)))))

; *** main *** ;

(defn print-usage [summary]
  (println "Usage:")
  (println "  nkv --init         Create a new .nkv config file with a new private key.")
  (println "  nkv <key>          Read a value for the given key.")
  (println "  nkv <key> <value>  Write a value for the given key.")
  (println "  nkv <key> --watch <command>  Watch for changes and run command.")
  (println "  nkv --qr             Print a QR code of the encrypted nsec for syncing.")
  (println)
  (println "Options:")
  (println summary))

(defn main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        arg-count (count arguments)]
    (when (:silent options)
      (set! (.-info log) identity))
    #_ (js/console.error "Arguments parsed:" (pr-str arguments))
    (cond
      errors
      (do (doseq [error errors] (js/console.error error))
          (print-usage summary)
          (js/process.exit 1))

      (:help options)
      (do (print-usage summary)
          (js/process.exit 0))

      (:init options)
      (handle-init)

      (:qr options)
      (let [{:keys [sk-bytes]} (load-config)]
        (handle-qr sk-bytes))

      :else
      (let [{:keys [sk-bytes relays]} (load-config)
            pk-hex (pubkey sk-bytes)]
        (cond
          (:watch options)
          (if (< arg-count 2)
            (do
              (js/console.error "Error: --watch requires a key and a command.")
              (print-usage summary)
              (js/process.exit 1))
            (let [[key-arg & command] arguments]
              (watch-key sk-bytes pk-hex key-arg command relays)))

          (not (or (= 1 arg-count) (= 2 arg-count)))
          (do (js/console.error "Error: Invalid number of arguments. Must be 1 or 2.")
              (print-usage summary)
              (js/process.exit 1))

          :else
          (let [[key-arg value-arg] arguments]
            (if (= 2 arg-count)
              ; Write operation
              (p/do!
                (write-value sk-bytes key-arg value-arg relays)
                ((.-info log) (str "Value set for key '" key-arg "'."))
                (js/process.exit 0))
              ; Read operation
              (p/let [retrieved-value (read-value sk-bytes pk-hex key-arg relays)]
                (if retrieved-value
                  (do
                    (println retrieved-value)
                    (js/process.exit 0))
                  (do
                    (js/console.error (str "No value found for key '" key-arg "'."))
                    (js/process.exit 1)))))))))))

(defn get-args [argv]
  (not-empty (js->clj (.slice argv
                              (if
                                (or
                                  (.endsWith
                                    (or (aget argv 1) "")
                                    "node_modules/nbb/cli.js")
                                  (.endsWith
                                    (or (aget argv 1) "")
                                    "bin/nbb"))
                                3 2)))))

(defonce started
  (apply main (get-args js/process.argv)))
