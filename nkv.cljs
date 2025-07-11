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
    ["ws$default" :as ws]
    ["child_process" :as cp]))

(useWebSocketImplementation ws)

(def app-name "cx.mccormick.nkv")
(def config-file-path ".nkv")
(def nostr-kind 30078)
(def default-relays ["wss://relay.damus.io" "wss://relay.nostr.band"])

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

(defn hmac-sha256 [secret data]
  (-> (.createHmac crypto "sha256" secret)
      (.update data)
      (.digest "hex")))

(defn create-finalized-event [sk-bytes clj-content d-identifier]
  (js/console.error "Creating event for d-identifier:" d-identifier "with content:" (pr-str clj-content))
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
    (js/console.error "event" event-template)
    (nostr/finalizeEvent event-template sk-bytes)))

(defn publish-event [event relays]
  (js/console.error "Publishing event to relays:" (clj->js relays))
  (p/catch
    (p/let [pool (SimplePool.)
            published (js/Promise.allSettled (.publish pool (clj->js relays) event))
            success? (seq (filter #(= (aget % "status") "fulfilled")
                                  published))]
      (js/console.error "published" published)
      (if success?
        (js/console.error "Event published to at least one relay.")
        (do
          (js/console.error "No relay accepted the event.")
          (js/process.exit 1))))
    (fn [err]
      (js/console.error err))))

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
    (js/console.error "Wrote config to" config-file-path)
    [new-sk-bytes "generated"]))

(defn get-relays [config-from-file]
  (cond
    (not-empty (aget js/process.env "NKV_RELAYS"))
    [(str/split (str/trim (aget js/process.env "NKV_RELAYS")) #",") "environment"]
    (seq (:relays config-from-file))
    [(:relays config-from-file) "config file"]
    :else
    [default-relays "defaults"]))

(defn load-config []
  (let [home-config-path (path/join (os/homedir) ".nkv")
        config-from-file (or (read-config config-file-path)
                             (read-config home-config-path))
        [relays relays-source]
        (get-relays config-from-file)
        decode-nsec (fn [nsec-str]
                      (when nsec-str
                        (try
                          (let [decoded (nostr/nip19.decode (str/trim nsec-str))]
                            (when (= (aget decoded "type") "nsec")
                              (aget decoded "data")))
                          (catch :default _ nil))))
        [sk-bytes nsec-source]
        (let [sk-from-env (decode-nsec (aget js/process.env "NKV_NSEC"))
              sk-from-file (decode-nsec (:nsec config-from-file))]
          (cond
            sk-from-env [sk-from-env "environment"]
            sk-from-file [sk-from-file "config file"]
            :else
            (generate-new-nkv-config relays)))]
    (js/console.error (str "Using nsec from " nsec-source " and relays from " relays-source "."))
    {:sk-bytes sk-bytes :relays relays}))

(defn write-value [sk-bytes key-arg value-arg relays]
  (js/console.error (str "Attempting to write key: " key-arg ", value: " value-arg))
  (let [content {:value value-arg}
        event (create-finalized-event sk-bytes content key-arg)]
    (publish-event event relays)))

(defn read-value [sk-bytes pk-hex key-arg relays]
  (js/console.error (str "Attempting to read key: " key-arg))
  (js/console.error "pk-key" pk-hex)
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
          (js/console.error "Latest event content decrypted:" (clj->js decrypted))
          (when decrypted (:value decrypted)))))
    (fn [err] (js/console.error err))))

(defn shell-escape [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn watch-key [sk-bytes pk-hex key-arg command relays]
  (js/console.error (str "Watching key: " key-arg ", and running command: " (str/join " " command)))
  (let [pool (SimplePool.)
        d-tag-value (hmac-sha256 sk-bytes (str app-name ":" key-arg))
        n-tag-value (hmac-sha256 sk-bytes app-name)
        event-filter (clj->js {:authors [pk-hex]
                               :kinds [nostr-kind]
                               :#d [d-tag-value]
                               :#n [n-tag-value]
                               :since (js/Math.floor (/ (js/Date.now) 1000))})]
    (.subscribe pool (clj->js relays) event-filter
                    (clj->js {:onevent (fn [event]
                                         (let [decrypted (decrypt-content sk-bytes (aget event "content"))
                                               value (:value decrypted)]
                                           (when value
                                             (js/console.error "New value received:" value)
                                             (let [cmd-str (str (str/join " " command) " " (shell-escape value))]
                                               (js/console.error "Executing:" cmd-str)
                                               (cp/exec cmd-str (fn [err stdout stderr]
                                                                  (when err (js/console.error "Exec error:" err))
                                                                  (when (not-empty stdout) (js/console.log stdout))
                                                                  (when (not-empty stderr) (js/console.error stderr))))))))}))
    (js/console.error "Subscription started. Waiting for events...")))

(def cli-options
  [["-w" "--watch" "Watch for changes and run a command on new values."]
   ["-i" "--init" "Create a new .nkv file with a new private key."]
   ["-h" "--help" "Show this help"]])

(defn print-usage [summary]
  (println "Usage:")
  (println "  nkv --init         Create a new .nkv config file with a new private key.")
  (println "  nkv <key>          Read a value for the given key.")
  (println "  nkv <key> <value>  Write a value for the given key.")
  (println "  nkv <key> --watch <command>  Watch for changes and run command.")
  (println)
  (println "Options:")
  (println summary))

(defn main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        arg-count (count arguments)]
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

      (if (fs/existsSync config-file-path)
        (do
          (js/console.error "Config already present in" config-file-path)
          (js/process.exit 1))
        (do
          (generate-new-nkv-config (get-relays nil))
          (js/process.exit 0)))

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
                (js/console.error (str "Value set for key '" key-arg "'."))
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
