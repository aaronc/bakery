(ns com.palletops.bakery.sente.core-async-bridge
  "Leaven components to propagate between sente events and a
  core.async channel."
  #+cljs
  (:require-macros
   [cljs.core.async.macros :refer [go-loop]])
  (:require
   #+clj [clojure.core.async :as async :refer [go-loop]]
   #+cljs [cljs.core.async :as async]
   [com.palletops.bakery.sente.protocols :refer [HandlerReturnable]]
   [com.palletops.leaven.protocols :refer [Startable Stoppable]]))

;; Note that sente already provides a receive channel, so there is no need for
;; a component to take events from sente and into a core.async channel.

#+clj
(defn send-event [send-fn {:keys [user-id event] :as ev} ex-handler]
  (try
    (send-fn user-id event)
    (catch Throwable e
      (ex-handler e))))

#+cljs
(defn send-event [send-fn {:keys [event timeout-ms cb-fn] :as ev} ex-handler]
  (try
    (if cb-fn
      (send-fn event timeout-ms cb-fn)
      (send-fn event))
    (catch js/Error e
      (ex-handler e))))

(defn channel->events
  [send-fn c ex-handler]
  {:pre [(fn? send-fn) c]}
  (go-loop []
    (when-let [ev (async/<! c)]
      (send-event send-fn ev ex-handler)
      (recur))))

(defrecord Bridge [channel sente loop-ch ex-handler]
  Startable
  (start [component]
    (if loop-ch
      component
      (assoc component
        :loop-ch (channel->events
                  (:send-fn (:channel-socket sente))
                  (:chan channel)
                  ex-handler))))
  Stoppable
  (stop [component]
    (assoc component :loop-ch nil)))

(defn bridge
  "Return a leaven component to forward a core-async channel via sente events.
  The `:channel` key specifies a leaven component for the target
  channel.  The `sente` key specifies a sente component to which
  events are sent.  The `ex-handler` may be used to specify an
  exception handler for the core.async `go-loop` that implements the
  forwarding.

  Event values on the channel are maps with `:user-id` and `:event`
  keys for the server side (clojure) and `:event`, `:timeout-ms` and
  `:cb-fn` keys for the client (clojurescript)."
  [{:keys [channel ex-handler] :as options}] {:pre [channel]}
  (map->Bridge (merge
                {:ex-handler
                 #+clj #(.printStackTrace %)
                 #+cljs #(.error js/console "Unexpected error %s" %)}
                options)))
