(ns funky-server.aleph-core
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [aleph.http :as http]
            [gloss.core :as gloss]
            [gloss.io :as gloss.io]
            [aleph.tcp :as tcp]
            [clj-time.local :as l]
            [clj-time.core :as t]
            [manifold.stream :as s]))

(defn new-uuid []
  (str (java.util.UUID/randomUUID)))

(def system-newline ;; This is in clojure.core but marked private.
  (System/getProperty "line.separator"))

(defn- init-player [stream]
  (let [last-msg-time (atom (l/local-now))
        in (async/chan (async/sliding-buffer 64) (map #(do (reset! last-msg-time (l/local-now)) %)))
        out (async/chan (async/dropping-buffer 256))
        player {:in-local (async/chan) :in in :out out :last-msg-time last-msg-time}]

    (s/connect stream in)
    (s/connect out stream {:upstream? true})

    (async/go-loop []
      (async/<! (async/timeout 1000))
      (if (> (t/in-millis (t/interval @last-msg-time (l/local-now))) 30000)
        (when-not (s/closed? stream) (s/close! stream))
        (recur)))

    (log/info "New player initialized")
    player))

(defn init-websocket [req]
  (if-let [socket (try
                    @(http/websocket-connection req {:headers {:Sec-WebSocket-Protocol "binary"}})
                    (catch Exception e nil))]
    socket
    (do
        (log/info "Not websocket")
        nil)))

(defn wait-for-disconnect [stream player]
  (let [done (async/chan)]
    (async/go
      (s/on-closed stream #(async/put! done (assoc player :disconnected? true)))
      (async/<! done))))


(defn handshake [player]
  (let [id (new-uuid)
        player (assoc player :id id)]
    (async/go
      (async/>! (:out player) (json/write-str {:msg "Welcome!" :id id}))
      (loop []
        (let [msg (json/read-str (or (async/<! (:in player)) "") :key-fn keyword :eof-error? false)]
          (if (nil? msg)
            nil
            (if (every? msg #{:gameType :maxPlayers :stepTime})
              (assoc player :game-info (clojure.set/rename-keys msg {:gameType :game-type :maxPlayers :max-players :stepTime :step-time}))
              (recur))))))))

(defn stream-write [out value]
  (async/go
    (async/>! out value)
    value))

(def protocol (gloss/string :utf-8 :delimiters ["\n"]))

(defn wrap-duplex-stream [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(gloss.io/encode protocol %) out)
      s)
    (s/splice
      out
      (gloss.io/decode-stream s protocol))))

(defn handle-new-connection [stream info players]
  (log/info "new connection" info)
  (async/go
    (some->> stream
             (wrap-duplex-stream protocol)
             init-player
             (handshake) (async/<!)
             (stream-write players) (async/<!)
             (wait-for-disconnect stream) (async/<!)
             (stream-write players) (async/<!)
             (log/info "Disconnected player" info))
    (log/info "end connection" info)))


(defn socket-server [port]
  (let [players (async/chan)
  		  aleph-server (tcp/start-server #(handle-new-connection %1 %2 players) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :players players :server aleph-server}))

(def protocol (gloss/string :utf-8 :delimiters ["\n"]))

(defn websocket-server [port]
  (let [players (async/chan)
        aleph-server (http/start-server #(handle-new-connection (init-websocket %1) "websocket" players) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :players players :server aleph-server}))

(defn add-step-to-msg [msg step]
  (-> msg
      (subs 0 (count msg))
      (str ",\"step\":" step "}")))

(defn start-ticker [step step-time out done join-ch]
  (let [start-time (l/local-now)]
    (async/go-loop []
      (let [run-time (t/in-millis (t/interval start-time (l/local-now)))
            step-time (- step-time (mod run-time step-time))]
        (when (-> [done (async/timeout step-time)] async/alts! second (not= done))
          ;;(log/info (str "step " @step " " (l/local-now)))
          (swap! step inc)
          (let [lock-msg (json/write-str {:lock (dec @step)})
                join-msg (async/poll! join-ch)]
            (async/>! out (if (nil? join-msg) [lock-msg] [lock-msg (add-step-to-msg join-msg (dec @step))])))
          (recur))))))

(defn start-game [type max-players step-time]
  (log/info "Starting game with type" type ", max-players" max-players ", step-time" step-time)
  (let [in (async/chan)
        in-mult (async/mult in)
        out (async/chan 1 cat)
        out-mult (async/mult out)
        in-sync-mult (async/mult (async/tap in-mult (async/chan (async/dropping-buffer 1) (filter #(.contains % "sync")))))
        join-ch (async/chan 8)
        step (atom 0)
        done (async/chan)
        sync-filter-xf (filter #(not (.contains % "sync")))
        vec-xf (map #(identity [%]))]

    (if (zero? step-time)
      (do
        (async/pipeline 1 out (comp vec-xf) (async/tap in-mult (async/chan))) ;; simple case for stepless games
        (async/pipeline 1 out vec-xf join-ch))
      (do
        (async/pipeline 1 out (comp sync-filter-xf vec-xf) (async/tap in-mult (async/chan)))
        (start-ticker step step-time out done join-ch)))

    {:in in
     :join-ch join-ch
     :out-mult out-mult
     :in-sync-mult in-sync-mult
     :players #{}
     :synced-players (atom [])
     :next-player-id 0
     :max-players max-players
     :type type
     :done done
     :seed (rand-int 500000) ;; something big but avoids overflow
     :close #(do (async/close! out) (async/close! in))}))


(defn active? [player]
  (< (t/in-millis (t/interval @(:last-msg-time player) (l/local-now)))
     2000))

(defn pick-syncer [game]
  (let [active (filter active? @(:synced-players game))]
    (if (> (count active) 0)
      (:id (rand-nth active))
      nil)))

(defn read-one-from-mult [m]
  (async/go
    (let [c (async/tap m (async/chan))
          r (async/<! c)]
      (async/untap m c)
      r)))

(defn request-sync [player game]
  (async/go
    (async/>! (:join-ch game) (json/write-str {:msg "join" :syncer (pick-syncer game)}))
    (async/>! (:out player) (async/<! (read-one-from-mult (:in-sync-mult game))))))

(defn add-player-id-to-msg [msg id]
  (-> msg
      (subs 0 (dec (count msg)))
      (str ",\"playerId\":" id "}")))


(defn add-player [player game]
  (let [player-id (:next-player-id game)
        new-game? (empty? (:players game))
        in (async/merge [(:in player) (:in-local player)])
        add-player-id-xf (map #(add-player-id-to-msg % player-id))
        filter-alive-xf (filter #(not (.contains % "alive")))]
    (log/info "Add player to game" (:type game) "with players" (:players game))
    (async/go
      (async/>! (:out player) (json/write-str {:join true :newGame new-game? :playerId player-id :seed (:seed game)}))
      (async/tap (:out-mult game) (:out player))
      (async/pipeline 1 (:in game) (comp filter-alive-xf add-player-id-xf) in false)
      (when-not new-game? (async/<! (request-sync player game)))
      (swap! (:synced-players game) #(conj % player)))
    (-> game
        (update :players conj (:id player))
        (update :next-player-id inc))))

(defn remove-player [player game]
  (if (contains? (:players game) (:id player))
    (do
      (async/put! (:in-local player) (json/write-str {:disconnected (:id player)}))
      (log/info "Removed player from game" (:type game) "Remaining players" (:players game))
      (swap! (:synced-players game) (fn [players] (filter #(not= (:id %) (:id player)) players)))
      (update game :players disj (:id player)))
    game))

(defn indices [pred coll]
   (keep-indexed #(when (pred %2) %1) coll))

(defn valid-game? [game-info game]
  (and (= (:type game) (:game-type game-info))
       (-> game :players count (< (:max-players game-info)))
       (not (nil? (pick-syncer game)))))

(defn pick-game-index [game-info games]
  (first (indices #(valid-game? game-info %) games)))

(defn join-game [games player]
  (let [game-info (:game-info player)
        {:keys [game-type max-players step-time]} game-info]
    (if-let [i (pick-game-index game-info games)]
      (assoc games i (add-player player (nth games i)))
      (->> (start-game game-type max-players step-time)
           (add-player player)
           (conj games)))))

(defn quit-game [games player]
  (let [games (map #(remove-player player %) games)
        { emptied true existing false } (group-by #(empty? (:players %)) games)]
    (doall (map #(async/close! (:done %)) emptied))
    (or existing [])))


(defn join-or-quit-game [games player]
  (if (:disconnected? player)
    (quit-game games player)
    (join-game games player)))

; NOTE/TODO: We use async/reduce with join-or-quit-game. It is kind of cool
; that the games are not in an atom but game ended up containing atoms anyways
; so putting games in an atom would make sense. It would make a couple of
; things cleaner to implement.
(defn start-lockstep-server [socket-server websocket-server]
  (let [players (async/chan 1)]
    (async/pipe (:players websocket-server) players)
    (async/pipe (:players socket-server) players)
    (async/reduce join-or-quit-game [] players)))

(defn echo-handler [s info]
  (s/connect s s))

(defn -main []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (log/error ex "Uncaught exception on" (.getName thread)))))
  (tcp/start-server echo-handler {:port 9120})
  (log/info "server done" (async/<!! (start-lockstep-server (socket-server 9121) (websocket-server 9122))))
  (log/info "quit"))
