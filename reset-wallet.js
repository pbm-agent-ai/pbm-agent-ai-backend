const { ethers } = require('ethers');
const PRIVATE_KEY = '1533eb22331231555b642165b9c1ae2df192c7de0a48c782327660055e882e5e';
const provider = new ethers.JsonRpcProvider('https://eth-sepolia.g.alchemy.com/v2/48rdcPmOO2K1i8uWVwVss');
const wallet = new ethers.Wallet(PRIVATE_KEY, provider);
const ABI = [
  "function walletLimit() view returns (uint256)",
  "function totalAllocated() view returns (uint256)",
  "function updateWalletLimit(uint256) external",
  "function sessionKeys(address) view returns (uint256 limit, uint256 spentAmount, uint256 expiry, bool isActive, string platform)",
  "function revokeSessionKey(address) external"
];
const contract = new ethers.Contract('0x5aef3f64351a4c5903ad9d1e4a2a8f89760e28f1', ABI, wallet);

// ethers v6에서는 getAddress()로 EIP-55 체크섬 변환
const OLD_AGENTS = [
  '0xabcd1234567890abcdef1234567890abcdef9999',
  '0xabcd1234567890abcdef1234567890abcdef1111',
  '0xabcd1234567890abcdef1234567890abcdef2222',
  '0xabcd1234567890abcdef1234567890abcdef3333',
  '0xabcd1234567890abcdef1234567890abcdef4444',
  '0xabcd1234567890abcdef1234567890abcdef5555',
  '0xabcd1234567890abcdef1234567890abcdef6666',
].map(a => ethers.getAddress(a));  // 체크섬 변환

async function main() {
  const D = 10n**18n;
  
  console.log('활성 세션키 조회 중...');
  for (const addr of OLD_AGENTS) {
    const sk = await contract.sessionKeys(addr);
    if (sk.isActive) {
      console.log(`활성 세션키: ${addr} (limit: ${sk.limit/D} KRW)`);
      const tx = await contract.revokeSessionKey(addr, { gasLimit: 100000 });
      await tx.wait();
      console.log(`  ✅ revoke 완료 - ${tx.hash}`);
    }
  }
  
  const [limit, allocated] = await Promise.all([contract.walletLimit(), contract.totalAllocated()]);
  console.log(`\n최종: walletLimit=${limit/D} KRW, totalAllocated=${allocated/D} KRW, 가용=${(limit-allocated)/D} KRW`);
}
main().catch(console.error);
