#!/usr/bin/env -S npx nbb
(ns nkv
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [promesa.core :as p]
    ["nostr-tools/pool" :refer [useWebSocketImplementation SimplePool]]
    ["fs" :as fs]
    ["nostr-tools" :as nostr]
    ["ws$default" :as ws]))

(useWebSocketImplementation ws)

(def app-name "cx.mccormick.nkv")
(def nsec-file-path ".nkv-nsec")
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

(defn create-finalized-event [sk-bytes clj-content d-identifier]
  (js/console.error "Creating event for d-identifier:" d-identifier "with content:" (pr-str clj-content))
  (let [encrypted-content (encrypt-content sk-bytes clj-content)
        event-template
        (clj->js
          {:kind nostr-kind
           :created_at (js/Math.floor (/ (js/Date.now) 1000))
           :tags [["d" (str app-name ":" d-identifier)]
                  ["n" app-name]]
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

(defn load-or-generate-nsec []
  (if-let [nsec-env (aget js/process.env "NKV_NSEC")]
    (try
      (let [decoded (nostr/nip19.decode (str/trim nsec-env))]
        (if (= (aget decoded "type") "nsec")
          (do
            (js/console.error "Loaded nsec from NKV_NSEC environment variable")
            (aget decoded "data"))
          (throw (js/Error. "Invalid nsec format in NKV_NSEC environment variable"))))
      (catch :default e
        (js/console.error "Error decoding nsec from NKV_NSEC environment variable:" e (.toString e))
        (js/process.exit 1)))
    (if (fs/existsSync nsec-file-path)
      (try
        (let [nsec-str (fs/readFileSync nsec-file-path "utf-8")
              decoded (nostr/nip19.decode (str/trim nsec-str))]
          (if (= (aget decoded "type") "nsec")
            (do
              (js/console.error "Loaded nsec from" nsec-file-path)
              (aget decoded "data"))
            (throw (js/Error. (str "Invalid nsec format in " nsec-file-path)))))
        (catch :default e
          (js/console.error "Error reading or decoding nsec from file:" e (.toString e))
          (js/process.exit 1)))
      (let [sk-bytes (nostr/generateSecretKey)
            nsec-str (nostr/nip19.nsecEncode sk-bytes)]
        (fs/writeFileSync nsec-file-path nsec-str "utf-8")
        (js/console.error "Generated new nsec and saved to" nsec-file-path)
        sk-bytes))))

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
            query-filter (clj->js {:authors [pk-hex]
                                   :limit 1
                                   :kinds [nostr-kind]
                                   :#d [(str app-name ":" key-arg)]})
            events (.querySync pool (clj->js relays) query-filter)]
      (when (seq events)
        (let [sorted-events (sort-by #(aget % "created_at") > events)
              latest-event (first sorted-events)
              decrypted (decrypt-content sk-bytes (aget latest-event "content"))]
          (js/console.error "Latest event content decrypted:" (clj->js decrypted))
          (when decrypted (:value decrypted)))))
    (fn [err] (js/console.error err))))

(def cli-options
  [["-h" "--help" "Show this help"]])

(defn print-usage [summary]
  (println "Usage:")
  (println "  nkv <key>          Read a value for the given key.")
  (println "  nkv <key> <value>  Write a value for the given key.")
  (println)
  (println "Options:")
  (println summary))

(defn main [& args]
  (let [sk-bytes (load-or-generate-nsec) ; This is synchronous
        pk-hex (pubkey sk-bytes)
        {:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
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

      (not (or (= 1 arg-count) (= 2 arg-count)))
      (do (js/console.error "Error: Invalid number of arguments. Must be 1 or 2.")
          (print-usage summary)
          (js/process.exit 1))

      :else
      (let [[key-arg value-arg] arguments]
        (if (= 2 arg-count)
          ; Write operation
          (p/do!
            (write-value sk-bytes key-arg value-arg default-relays)
            (js/console.error (str "Value set for key '" key-arg "'."))
            (js/process.exit 0))
          ; Read operation
          (p/let [retrieved-value (read-value sk-bytes pk-hex key-arg default-relays)]
            (if retrieved-value
              (do
                (println retrieved-value)
                (js/process.exit 0))
              (do
                (js/console.error (str "No value found for key '" key-arg "'."))
                (js/process.exit 1)))))))))

(defn get-args [argv]
  (not-empty (js->clj (.slice argv
                              (if 
                                (or
                                  (.endsWith
                                    (or (aget argv 1) "")
                                    "node_modules/nbb/cli.js")
                                  (.endsWith
                                    (or (aget argv 1) "")
                                    "/bin/nbb"))
                                3 2)))))

(defonce started
  (apply main (get-args js/process.argv)))
