(ns flow-protocol
  (:require [hyperfiddle.rcf :refer [tests % tap with]]
            [missionary.core :as m]))

; Flow Protocol -- https://github.com/leonoel/flow
; Draft, this needs substantial clarificaftion.

; To truly understand Missionary (and backpressure) you must understand the state
; transitions that continuous and discrete flows go through as the computation
; unfolds. So far, this has been hand-waved over by using m/reduce to drive the
; flows at the entrypoint (I have repeatedly mentioned "consuming them as fast
; as possible"). So let's understand exactly what this means, "to consume a flow".
; What is m/reduce doing under the hood?


(comment ; let's not use RCF for the first example as this is nuanced. Run line by line
  "low level flow protocol"
  (def !a (atom 0))
  (def >a (m/latest inc (m/watch !a))) ; real m/watch, which is equivalent to m/observe + m/relieve as above.

  ; Warning: you must run the forms *exactly* as I direct of you'll hang your
  ; session. Refresh and start over when this happens.
  ; Flows are state machines and their low level APIs are only well defined in
  ; certain states. This is the flow protocol and you MUST NOT violate it!


  ; Flows are encoded as functions. There are no Clojure protocols, i.e., the flow
  ; protocol is *dependency free* and can be implemented without dependency on Missioary.

  (fn? >a) ; => true
  (println >a)
  ; #object[Function]


  ; Missionary flows are programs. A running program is a process.
  ; (Program: stateless, static, data. Process: stateful, dynamic, control flow.)
  ; Flows happen to be encoded as thunks, none the less this is just a
  ; representation or data encoding. Thunks are immutable values.

  ; Run the flow by invoking it with two callbacks:
  (def it (>a ; run the flow, returning a process
            (fn notify [] (println ::notify)) ; called when a value is available to be sampled
            (fn terminate [] (println ::terminate)))) ; called when the process terminates

  ; ::notify -- printed at console

  ; `it` (i.e. iterator) is a handle to the running process, that you use to
  ; consume successive values as they become ready and notify.
  ; `::notify` is printed by the notify callback right away, signalling
  ; to the downstream *consumer* (us) that the iterator (running flow) now has a
  ; value available to be *consumed*.

  ; `notify` callback is called when an input has changed, signalling that a new
  ; value is available. Here, the watch emits the initial value, which is buffered
  ; until it is pulled through. `inc` has not happened yet, because m/latest
  ; is a continous pipeline stage!

  ; Once the flow notifies, or is "ready to transfer", it has a value available
  ; to be consumed by the next stage of the pipeline.
  ; Consume the value from the iterator with `deref`:
  @it ; => 1
  ; 1 is the result of (inc 0).
  ; 0 was the initial value of the watch, which was bufferred by the watch until
  ; transferred. "transfer" means the action of the subsequent pipeline step accepting
  ; a value from the antecedent step, i.e., consumed.

  ; The process is now parked until the atom changes, at which point it will notify again.
  ; you MUST NOT deref when the flow is not in ready state, if you do you'll hang your session.

  ; Note: Manipulating raw flow iterators like this is a low level operation.
  ; Real world applications don't acutally do this, they consume with `m/reduce`
  ; instead, which abstracts over these details.

  ; When will this process next notify? When the atom changes. Let's do that now:
  (reset! !a 5)
  ; ::notify -- the running flow has notified that it is in ready state.

  ; That means we can consume the value at our convenience:
  @it ; => 6

  ; Note: we can choose NOT to consume the value immediately as well!
  ; That's the backpressure part. By not consuming, we're implicitly telling
  ; the producers that they must not try to push more values through, because
  ; we're not ready (e.g. over capacity) and can't handle it!
  ; Another example, maybe we want to only render the DOM on requestAnimationFrame.


  (swap! !a inc)
  ; ::notify
  @it ; => 7

  ; two in rapid succession, no transfer in between
  (do (swap! !a inc) (swap! !a inc))
  ; ::notify
  @it ; => 9. 8 was skipped!

  ; Q: Why didn't it crash?
  ; A: because of the m/relieve inside of m/watch

  ; Shutdown
  (it) ; => nil -- cancel
  ; ::notify -- another notify!
  ; Why? because no cleanup effects can happen until you sample!

  (try
    @it
    (catch #?(:cljs :default :clj Exception) e
      #?(:cljs (js/console.log e) :clj (println e))
      e)) ; => #object[Object [object Object]]
  ; ::terminate
  ; missionary.Cancelled {message: 'Watch cancelled.'}

  ; Q: Why the try/catch boilerplate?
  ; AL Because I knew the final value would be an exception, and the ClojureScript
  ; exception isn't serializable so gets collapsed to [Object object] en route
  ; to the remote REPL. So for teaching I opted to print it in browser with console.warn.
  )

; leonoel m/watch does relieve with discard
; it is truly equivalent to m/observe + m/relieve as you mentioned
; Dustin Getz oh, ok, so it is possible to consume all events in a single threaded context so long as the consumer keeps up and sees each value buffered by relieve. In a multi threaded context, we can miss values
; leonoel yes, that's the point of continuous time
; continuous time is resolution independence, i.e. reader decoupled from writer
; leonoel it would not make sense to crash the writers due to backpressure
; leonoel a database can always accept new transactions, even if nobody runs any query

; https://github.com/leonoel/missionary/blob/master/java/missionary/impl/Reduce.java
; https://github.com/leonoel/missionary/blob/master/java/missionary/impl/Watch.java