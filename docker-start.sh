#!/bin/bash

# My Platform - Docker Quick Start Script

set -e

echo "🐳 My Platform Docker Setup"
echo "================================"

# Check if .env exists
if [ ! -f .env ]; then
    echo "⚠️  .env file not found!"
    echo "📝 Creating .env from .env.example..."
    cp .env.example .env
    echo "✅ Please edit .env file and set your passwords!"
    echo ""
    echo "Required settings:"
    echo "  - MYSQL_ROOT_PASSWORD"
    echo "  - MYSQL_PASSWORD"
    echo "  - REDIS_PASSWORD"
    echo "  - JWT_SECRET"
    echo ""
    read -p "Press Enter to continue after editing .env..."
fi

# Check Docker
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed!"
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    echo "❌ Docker Compose is not installed!"
    exit 1
fi

echo "✅ Docker and Docker Compose are installed"
echo ""

# Menu
echo "Select an option:"
echo "1) Start all services (build + run)"
echo "2) Start all services (run only)"
echo "3) Stop all services"
echo "4) View logs"
echo "5) Restart backend"
echo "6) Clean up (remove containers and volumes)"
echo "7) Exit"
echo ""
read -p "Enter option (1-7): " option

case $option in
    1)
        echo "🔨 Building and starting all services..."
        docker-compose up -d --build
        echo ""
        echo "✅ Services started!"
        echo "🌐 Application: http://localhost"
        echo "📚 Swagger: http://localhost/swagger-ui/index.html"
        echo ""
        echo "📊 View logs: docker-compose logs -f"
        ;;
    2)
        echo "🚀 Starting all services..."
        docker-compose up -d
        echo ""
        echo "✅ Services started!"
        ;;
    3)
        echo "🛑 Stopping all services..."
        docker-compose down
        echo "✅ Services stopped!"
        ;;
    4)
        echo "📊 Viewing logs (Ctrl+C to exit)..."
        docker-compose logs -f
        ;;
    5)
        echo "🔄 Restarting backend..."
        docker-compose restart backend
        echo "✅ Backend restarted!"
        ;;
    6)
        read -p "⚠️  This will remove all containers and data! Continue? (y/N): " confirm
        if [ "$confirm" = "y" ] || [ "$confirm" = "Y" ]; then
            echo "🗑️  Cleaning up..."
            docker-compose down -v
            echo "✅ Cleanup complete!"
        else
            echo "Cancelled."
        fi
        ;;
    7)
        echo "👋 Goodbye!"
        exit 0
        ;;
    *)
        echo "❌ Invalid option!"
        exit 1
        ;;
esac

