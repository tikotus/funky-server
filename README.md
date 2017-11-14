# funky-server

A socket server using Aleph for multiplayer games using lockstep protocol.
It uses core/async a lot.

Games can connect to the server and give a game id. He will be put in a game
with other players who gave the same game id. A max number of players can also
be given and a new game instance will be created if a player doesn't fit in any.

A game works as a broadcaster and lockstepper. All events sent by clients are
broadcasted to other clients in the same game. Clients will receive the same
messages in the same order. With a specified interval the server broadcasts a
lockstep message which the client can use to make the game logic synchronized
and deterministic by evaluating events on every lockstep and advancing the game
logic a fixed amount between steps.

Special logic is implemented to synchronise new players with the current game
state. The server doesn't hold the game state so it must ask one of the players
to send the game state. Sync messages are big and are piped only to the players
who have requested a sync to reduce bandwidth usage. Also the server makes sure
that sync messages are sent right after a lockstep to make the client side
implementation easier.


## Usage

Run with

    lein run

## License

Copyright © 2017 Johannes Ahvenniemi

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
