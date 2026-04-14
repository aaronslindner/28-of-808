import os
from datetime import datetime, timedelta

from fastapi import FastAPI, HTTPException, Header
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
                updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )
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
async def get_leaderboard():
    async with pool.acquire() as con:
        rows = await con.fetch("""
            SELECT player_name, wealth, updated_at
            FROM leaderboard
            ORDER BY wealth DESC
            LIMIT 100
        """)
    return [
        {
            "player_name": r["player_name"],
            "wealth": r["wealth"],
            "updated_at": r["updated_at"].isoformat(),
        }
        for r in rows
    ]


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
