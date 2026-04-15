import os
from datetime import datetime, timedelta

from typing import Optional
from fastapi import FastAPI, HTTPException, Header, Query
from pydantic import BaseModel
import asyncpg

app = FastAPI(title="UNM Leaderboard")

DATABASE_URL = os.environ.get("DATABASE_URL")
API_KEY = os.environ.get("UNM_API_KEY", "changeme")

pool: asyncpg.Pool | None = None


@app.on_event("startup")
async def startup():
    global pool
    pool = await asyncpg.create_pool(DATABASE_URL, min_size=1, max_size=5)
    async with pool.acquire() as con:
        await con.execute("""
            CREATE TABLE IF NOT EXISTS leaderboard (
                player_name TEXT PRIMARY KEY,
                wealth      BIGINT  NOT NULL,
                prestige    INT     NOT NULL DEFAULT 0,
                updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )
        """)
        await con.execute("""
            ALTER TABLE leaderboard ADD COLUMN IF NOT EXISTS prestige INT NOT NULL DEFAULT 0
        """)


@app.on_event("shutdown")
async def shutdown():
    if pool:
        await pool.close()


class WealthUpdate(BaseModel):
    player_name: str
    wealth: int


@app.post("/wealth")
async def update_wealth(data: WealthUpdate, x_api_key: str = Header()):
    if x_api_key != API_KEY:
        raise HTTPException(401, "Invalid API key")
    if data.wealth < 0:
        raise HTTPException(400, "Wealth cannot be negative")
    if not data.player_name or len(data.player_name) > 12:
        raise HTTPException(400, "Invalid player name")

    async with pool.acquire() as con:
        await con.execute("""
            INSERT INTO leaderboard (player_name, wealth, updated_at)
            VALUES ($1, $2, now())
            ON CONFLICT (player_name) DO UPDATE SET
                wealth     = EXCLUDED.wealth,
                updated_at = EXCLUDED.updated_at
        """, data.player_name, data.wealth)
    return {"ok": True}


@app.get("/leaderboard")
async def get_leaderboard(
    player: Optional[str] = Query(None),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
):
    offset = (page - 1) * page_size
    async with pool.acquire() as con:
        total = await con.fetchval("SELECT COUNT(*) FROM leaderboard")
        rows = await con.fetch("""
            SELECT player_name, wealth, prestige, updated_at
            FROM leaderboard
            ORDER BY prestige DESC, wealth DESC
            LIMIT $1 OFFSET $2
        """, page_size, offset)

        player_rank = None
        if player:
            rank_row = await con.fetchrow("""
                SELECT rank, player_name, wealth, prestige FROM (
                    SELECT player_name, wealth, prestige,
                           RANK() OVER (ORDER BY prestige DESC, wealth DESC) as rank
                    FROM leaderboard
                ) sub
                WHERE player_name = $1
            """, player)
            if rank_row:
                player_rank = {
                    "rank": rank_row["rank"],
                    "player_name": rank_row["player_name"],
                    "wealth": rank_row["wealth"],
                    "prestige": rank_row.get("prestige", 0),
                }

    return {
        "player_rank": player_rank,
        "page": page,
        "total_pages": max(1, (total + page_size - 1) // page_size),
        "leaderboard": [
            {
                "player_name": r["player_name"],
                "wealth": r["wealth"],
                "prestige": r["prestige"],
                "updated_at": r["updated_at"].isoformat(),
            }
            for r in rows
        ],
    }


class PrestigeRequest(BaseModel):
    player_name: str


@app.post("/prestige")
async def prestige(data: PrestigeRequest, x_api_key: str = Header()):
    if x_api_key != API_KEY:
        raise HTTPException(401, "Invalid API key")
    if not data.player_name or len(data.player_name) > 12:
        raise HTTPException(400, "Invalid player name")

    async with pool.acquire() as con:
        row = await con.fetchrow(
            "SELECT prestige FROM leaderboard WHERE player_name = $1",
            data.player_name,
        )
        if not row:
            raise HTTPException(404, "Player not found on leaderboard")

        new_prestige = row["prestige"] + 1
        await con.execute("""
            UPDATE leaderboard
            SET prestige = $1, updated_at = now()
            WHERE player_name = $2
        """, new_prestige, data.player_name)
    return {"ok": True, "prestige": new_prestige}


@app.post("/prestige/reset")
async def reset_prestige(data: PrestigeRequest, x_api_key: str = Header()):
    if x_api_key != API_KEY:
        raise HTTPException(401, "Invalid API key")
    if not data.player_name or len(data.player_name) > 12:
        raise HTTPException(400, "Invalid player name")

    async with pool.acquire() as con:
        await con.execute("""
            UPDATE leaderboard
            SET prestige = 0, updated_at = now()
            WHERE player_name = $1
        """, data.player_name)
    return {"ok": True, "prestige": 0}


@app.delete("/leaderboard")
async def clear_leaderboard(x_api_key: str = Header()):
    if x_api_key != API_KEY:
        raise HTTPException(401, "Invalid API key")
    async with pool.acquire() as con:
        await con.execute("DELETE FROM leaderboard")
    return {"ok": True}


@app.get("/health")
async def health():
    return {"status": "ok"}
